package com.hermes.client.data.remote

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.hermes.client.AppVisibility
import com.hermes.client.data.prefs.SettingsRepository
import com.hermes.client.data.repository.JobsRepository
import com.hermes.client.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foreground "true push" lane for cron-job results, on top of Hermes' built-in ntfy platform
 * plugin (zero server-side code — the design agreed with the Hermes agent).
 *
 * Jobs configured with `deliver = "ntfy:<topic>"` make the server POST the finished result to an
 * ntfy server; while this app is in the foreground we hold one SSE subscription to the same
 * topic (`{url}/{topic}/sse`, ntfy's EventSource endpoint) so a finished job re-syncs — and
 * notifies — within ~a second instead of waiting for the 15-minute WorkManager reconcile. The
 * subscription is dropped when the app goes to the background (Doze kills long sockets anyway);
 * the reconcile worker remains the delivery guarantee.
 *
 * Push messages are treated as a *hint*, never as the source of truth: the resulting sync goes
 * through [JobsRepository.sync] and its executions-ledger dedupe, so a replayed or forged ntfy
 * message can never produce a duplicate notification on its own.
 */
@Singleton
class NtfyPushController @Inject constructor(
    private val settings: SettingsRepository,
    private val jobsRepository: JobsRepository,
    private val gson: Gson,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // stream stays open
        .retryOnConnectionFailure(true)
        .build()

    private var pulseJob: Job? = null
    private var subscriptionJob: Job? = null
    private var foreground = false
    private var started = false

    /** Pulses emitted by the SSE stream; conflated so a burst triggers a single sync. */
    private val pulses = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Idempotent; call once from the Application. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            pulses.collectLatest {
                runCatching { jobsRepository.sync(notify = true) }
                // The ntfy delivery can land a moment before the executions ledger flips to a
                // terminal state; one delayed retry catches that race.
                delay(LEDGER_SETTLE_DELAY_MS)
                runCatching { jobsRepository.sync(notify = true) }
            }
        }
        // A config change re-evaluates the subscription without waiting for a visibility flip.
        scope.launch {
            settings.settings.collect { restartIfActive() }
        }
    }

    /** Driven by [AppVisibility] activity callbacks in the Application. */
    fun setForeground(active: Boolean) {
        foreground = active
        restartIfActive()
    }

    private fun restartIfActive() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        if (!foreground) return
        // Cached mirrors are kept warm by SettingsRepository; read them synchronously.
        val url = settings.cachedJobsPushUrl
        val topic = settings.cachedJobsPushTopic
        if (url.isBlank() || topic.isBlank()) return
        subscriptionJob = scope.launch { subscribeLoop(url.trim().trimEnd('/'), topic.trim()) }
    }

    private suspend fun subscribeLoop(baseUrl: String, topic: String) {
        val token = settings.cachedJobsPushToken
        var attempt = 0
        while (true) {
            try {
                stream(baseUrl, topic, token)
                    .collect {
                        attempt = 0 // a live message proves the subscription works
                        pulses.tryEmit(Unit)
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation // config/visibility changed, or the scope died
            } catch (_: Exception) {
                // network drop / server restart: backoff and reconnect below
            }
            attempt += 1
            delay(minOf(RETRY_BASE_MS shl (attempt - 1).coerceAtMost(5), RETRY_MAX_MS))
        }
    }

    private fun stream(baseUrl: String, topic: String, token: String) = callbackFlow {
        val builder = Request.Builder()
            .url("$baseUrl/$topic/sse")
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
        // Credentials only travel over TLS (or opted-in plaintext loopback): any other http://
        // ntfy server gets an anonymous subscription.
        val url = baseUrl.toHttpUrlOrNull()
        if (token.isNotBlank() && url != null &&
            CredentialPolicy.allows(url, settings.cachedAllowLoopbackCredentials)
        ) {
            builder.header("Authorization", "Bearer $token")
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                // ntfy keeps the stream warm with keepalive/open events; only real "message"
                // events carry a job result.
                val payload = runCatching { gson.fromJson(data, JsonObject::class.java) }.getOrNull()
                if (payload?.get("event")?.asString == "message") trySend(Unit)
            }

            override fun onClosed(eventSource: EventSource) {
                close(IOException("ntfy stream closed"))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: IOException("ntfy stream failed (HTTP ${response?.code})"))
            }
        }
        val source = EventSources.createFactory(client).newEventSource(builder.build(), listener)
        awaitClose { source.cancel() }
    }

    private companion object {
        const val RETRY_BASE_MS = 3_000L
        const val RETRY_MAX_MS = 60_000L
        const val LEDGER_SETTLE_DELAY_MS = 6_000L
    }
}
