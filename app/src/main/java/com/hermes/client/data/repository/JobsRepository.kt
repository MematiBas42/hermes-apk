package com.hermes.client.data.repository

import com.hermes.client.AppVisibility
import com.hermes.client.ChatVisibility
import com.hermes.client.HermesApp
import com.hermes.client.JobsVisibility
import com.hermes.client.R
import com.hermes.client.data.local.HermesDatabase
import com.hermes.client.data.local.JobEntity
import com.hermes.client.data.model.CronJob
import com.hermes.client.data.remote.HermesApi
import com.hermes.client.data.remote.dto.CronJobDto
import com.hermes.client.data.remote.dto.JobCreateRequest
import com.hermes.client.data.remote.dto.JobPatchRequest
import com.hermes.client.data.remote.dto.toEpochMillisOrNull
import com.hermes.client.notifications.RunCompletionNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hermes 自带定时任务（cron jobs）的客户端仓库。
 *
 * Push architecture (designed together with the Hermes agent reading its own source):
 *  - **Background baseline**: the existing 15-minute WorkManager reconcile also polls
 *    `GET /api/jobs?include_disabled=true` — one cheap request — and notifies on new terminal
 *    executions. The de-duplication key is `latest_execution.id` (a fresh uuid per fire from
 *    the server's executions ledger), never the `last_run_at` string.
 *  - **Foreground instant push**: Hermes ships an ntfy platform plugin; jobs with
 *    `deliver = "ntfy:<topic>"` are pushed to an ntfy server and this app subscribes to the
 *    same topic's SSE stream while it is open
 *    (see [com.hermes.client.data.remote.NtfyPushController]) and re-syncs immediately.
 *    Zero server-side code either way.
 *  - **Result transcripts**: every cron fire creates a session `cron_{job_id}_{timestamp}`
 *    (source="cron") holding the full transcript — those flow into the regular session list,
 *    and notifications deep-link straight into them.
 *  - `no_agent` script jobs create **no** session; their notifications fall back to the
 *    status/error text and open the jobs screen instead.
 */
@Singleton
class JobsRepository @Inject constructor(
    private val api: HermesApi,
    private val db: HermesDatabase,
    private val notifier: RunCompletionNotifier,
    private val appVisibility: AppVisibility,
    private val chatVisibility: ChatVisibility,
    private val jobsVisibility: JobsVisibility,
) {
    private val jobDao get() = db.jobDao()
    private val syncMutex = Mutex()

    fun observeJobs(): Flow<List<CronJob>> =
        jobDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun get(id: String): CronJob? = jobDao.get(id)?.toDomain()

    /**
     * Pull all jobs (enabled + disabled — exhausted `repeat` counts are stored disabled on the
     * server, hiding them would make finished jobs vanish) and mirror them into Room. When
     * [notify] is set, every execution we have not seen before that reached a terminal state
     * posts a local notification. Returns the number of jobs merged.
     */
    suspend fun sync(notify: Boolean = true): Result<Int> = withContext(Dispatchers.IO) {
        if (!syncMutex.tryLock()) return@withContext Result.success(-1) // a sync is in flight
        try {
            runCatching {
                val remote = api.listJobs(includeDisabled = true).jobs
                val now = System.currentTimeMillis()
                val fresh = mutableListOf<Pair<CronJobDto, JobEntity>>()

                remote.forEach { dto ->
                    val existing = jobDao.get(dto.id)
                    val entity = dto.toEntity(now, existing)
                    jobDao.upsert(entity)
                    if (isNewTerminalExecution(existing?.lastExecutionId, dto)) fresh += dto to entity
                }
                // Fresh mirror: jobs deleted server-side disappear locally as well.
                val remoteIds = remote.map { it.id }.toSet()
                jobDao.allIds().filter { it !in remoteIds }.forEach { jobDao.delete(it) }

                if (notify) fresh.forEach { (dto, entity) -> notifyExecution(entity, dto) }
                remote.size
            }
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun notifyExecution(entity: JobEntity, dto: CronJobDto) {
        val execution = dto.latestExecution ?: return
        val sessionId = latestSessionFor(entity.id)
        if (sessionId != null) {
            jobDao.updateExecution(
                id = entity.id,
                executionId = execution.id,
                status = execution.status,
                sessionId = sessionId,
            )
        }
        // Watching the jobs screen or the very result session: the live UI already shows it.
        if (appVisibility.isForeground &&
            (jobsVisibility.screenVisible || chatVisibility.foregroundSessionId == sessionId)
        ) {
            return
        }
        // The agent run and the delivery are tracked separately: `delivery_failed` means the run
        // itself SUCCEEDED and the transcript exists — the result is fully viewable in the app.
        // Only `error` / `blocked_config` mean the task truly did not run to completion.
        val runFailed = dto.lastStatus == "error" || dto.lastStatus == "blocked_config"
        val success = execution.status == "completed" && !runFailed
        val body = when {
            success -> HermesApp.string(R.string.job_notif_success, dto.repeat?.completed ?: entity.repeatCompleted)
            runFailed -> (dto.lastError ?: execution.error ?: HermesApp.string(R.string.job_notif_failed, execution.status ?: "")).take(200)
            // failed / unknown execution, no last_status yet
            else -> (execution.error ?: HermesApp.string(R.string.job_notif_failed, execution.status ?: "")).take(200)
        }
        notifier.notifyJob(
            jobId = entity.id,
            sessionId = sessionId,
            title = HermesApp.string(R.string.job_notif_title, entity.name),
            message = body,
            success = success,
        )
    }

    /**
     * The transcript for a fire is the session `cron_{job_id}_{YYYYmmdd_HHMMSS}` — the
     * zero-padded timestamp makes the lexicographic max the newest run. Ids are minted in the
     * gateway's local timezone, so prefix matching (the same convention as the server's own
     * `list_cron_job_runs`) is the reliable join.
     */
    suspend fun latestSessionFor(jobId: String): String? = runCatching {
        latestCronSessionId(jobId, api.listSessions(source = "cron", limit = 200).data.map { it.id })
    }.getOrNull()

    // ------------------------------------------------------------------ CRUD passthroughs

    suspend fun create(
        name: String,
        schedule: String,
        prompt: String,
        deliver: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.createJob(
                JobCreateRequest(
                    name = name.trim(),
                    schedule = schedule.trim(),
                    prompt = prompt.trim(),
                    deliver = deliver.trim().ifBlank { "local" },
                ),
            )
            sync(notify = false)
            Unit
        }
    }

    /**
     * Partial update. Only fields the user actually changed are sent: the dialog initialises the
     * schedule field with the job's *display* string ("every 1m", "once at …"), which is not
     * guaranteed to round-trip through the server's parser, so an untouched schedule must never
     * be PATCHed back.
     */
    suspend fun patch(
        id: String,
        name: String?,
        schedule: String?,
        prompt: String?,
        deliver: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JobPatchRequest(
                name = name?.trim()?.takeIf { it.isNotBlank() },
                schedule = schedule?.trim()?.takeIf { it.isNotBlank() },
                prompt = prompt?.trim()?.takeIf { it.isNotBlank() },
                deliver = deliver?.trim()?.takeIf { it.isNotBlank() },
            )
            api.patchJob(id, body)
            sync(notify = false)
            Unit
        }
    }

    /**
     * Point a job's delivery at "local". The one-tap repair for jobs created back when a
     * messaging platform (e.g. a since-retired WeChat bridge) was the delivery target: the run
     * keeps succeeding while every delivery attempt fails noisily.
     */
    suspend fun setDeliverLocal(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.patchJob(id, JobPatchRequest(deliver = "local"))
            sync(notify = false)
            Unit
        }
    }

    suspend fun pause(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.pauseJob(id) }.map { Unit }
    }

    suspend fun resume(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.resumeJob(id) }.map { Unit }
    }

    /** Trigger now; the gateway scheduler picks the job up within its 60 s tick. */
    suspend fun runNow(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { api.runJob(id) }.map { Unit }
    }

    suspend fun delete(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.deleteJob(id)
            jobDao.delete(id)
            Unit
        }
    }
}

internal fun toEntityInternal(dto: CronJobDto, now: Long, existing: JobEntity?): JobEntity = dto.toEntity(now, existing)

/**
 * A fresh terminal fire: the executions-ledger uuid changed and the new execution reached a
 * terminal status (`completed` happy path, `failed` must alert, `unknown` = worker died — also
 * worth surfacing). `claimed`/`running` are waited for; `last_run_at` strings are never compared
 * because re-fires and edits make them unreliable as identity.
 */
internal fun isNewTerminalExecution(previousExecutionId: String?, dto: CronJobDto): Boolean {
    val execution = dto.latestExecution ?: return false
    val id = execution.id?.takeIf { it.isNotBlank() } ?: return false
    if (id == previousExecutionId) return false
    return execution.status == "completed" ||
        execution.status == "failed" ||
        execution.status == "unknown"
}

/**
 * Newest transcript session for a job: cron sessions are `cron_{job_id}_{YYYYmmdd_HHMMSS}` and
 * the timestamp is zero-padded, so the lexicographic max of the prefix matches is the latest run
 * (the same join the server's `list_cron_job_runs` performs).
 */
internal fun latestCronSessionId(jobId: String, sessionIds: List<String>): String? =
    sessionIds.filter { it.startsWith("cron_${jobId}_") }.maxOrNull()

private fun CronJobDto.toEntity(now: Long, existing: JobEntity?): JobEntity = JobEntity(
    id = id,
    name = name,
    prompt = prompt,
    schedule = schedule?.expr ?: "",
    scheduleDisplay = scheduleDisplay ?: schedule?.display ?: schedule?.expr ?: "",
    deliver = deliver ?: "local",
    enabled = enabled,
    noAgent = noAgent,
    // "paused" is the explicit pause state; a disabled recurring job (PATCH enabled=false)
    // behaves the same way for the UI. Repeat-exhausted jobs are NOT paused — they finished.
    paused = (state == "paused") || (!enabled && (repeat?.times == null)),
    createdAt = createdAt.toEpochMillisOrNull() ?: existing?.createdAt ?: now,
    nextRunAt = nextRunAt.toEpochMillisOrNull() ?: 0,
    lastRunAt = lastRunAt.toEpochMillisOrNull() ?: existing?.lastRunAt ?: 0,
    lastStatus = lastStatus,
    lastError = lastError,
    lastDeliveryError = lastDeliveryError,
    failureStreak = failureStreak,
    repeatCompleted = repeat?.completed ?: 0,
    repeatTimes = repeat?.times,
    // Local-only memory (notification dedupe + deep-link target), never clobbered by a sync.
    lastExecutionId = existing?.lastExecutionId,
    lastExecutionStatus = existing?.lastExecutionStatus,
    lastSessionId = existing?.lastSessionId,
    updatedAt = now,
)

private fun JobEntity.toDomain() = CronJob(
    id = id,
    name = name,
    prompt = prompt,
    schedule = scheduleDisplay.ifBlank { schedule },
    deliver = deliver,
    enabled = enabled,
    paused = paused,
    noAgent = noAgent,
    nextRunAt = nextRunAt,
    lastRunAt = lastRunAt,
    lastStatus = lastStatus,
    lastError = lastError,
    lastDeliveryError = lastDeliveryError,
    failureStreak = failureStreak,
    repeatCompleted = repeatCompleted,
    repeatTimes = repeatTimes,
    lastSessionId = lastSessionId,
    lastExecutionId = lastExecutionId,
    updatedAt = updatedAt,
)
