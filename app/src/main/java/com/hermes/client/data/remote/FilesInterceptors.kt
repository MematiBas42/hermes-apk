package com.hermes.client.data.remote

import com.hermes.client.HermesApp
import com.hermes.client.R
import com.hermes.client.data.prefs.SettingsRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Resolves every outgoing request onto the user-configured **file-service** base URL and attaches
 * the Bearer token. Mirrors [DynamicUrlInterceptor] but targets the companion file service.
 */
class FilesUrlInterceptor(
    private val settings: SettingsRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val base = settings.cachedFilesBaseUrl.trim()
        if (base.isBlank()) {
            throw IOException(HermesApp.string(R.string.files_url_not_configured))
        }
        val newUrl = UrlResolver.resolve(base, original.url)
        val builder = original.newBuilder().url(newUrl)
        val key = settings.cachedApiKey.trim()
        // Credentials travel over TLS, or plaintext loopback only if the user opted in (see CredentialPolicy).
        if (key.isNotEmpty() && CredentialPolicy.allows(newUrl, settings.cachedAllowLoopbackCredentials) &&
            original.header("Authorization") == null
        ) {
            builder.header("Authorization", "Bearer $key")
        }
        return chain.proceed(builder.build())
    }
}

/**
 * Adds the Bearer token only for URLs below the file-service base (host, port and whole path
 * segments are compared) that [CredentialPolicy] allows: HTTPS, or plaintext loopback when the
 * user opted in. Used by Coil so Markdown images served by the file service can be loaded without
 * leaking the key to arbitrary hosts.
 */
class FilesAuthInterceptor(
    private val settings: SettingsRepository,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val base = settings.cachedFilesBaseUrl.trim().trimEnd('/')
        val key = settings.cachedApiKey.trim()
        val baseUrl = base.toHttpUrlOrNull()
        val matches = baseUrl != null && key.isNotEmpty() &&
            CredentialPolicy.allows(request.url, settings.cachedAllowLoopbackCredentials) &&
            CredentialPolicy.isUnder(request.url, baseUrl)
        return if (matches) {
            chain.proceed(
                request.newBuilder().header("Authorization", "Bearer $key").build(),
            )
        } else {
            chain.proceed(request)
        }
    }
}
