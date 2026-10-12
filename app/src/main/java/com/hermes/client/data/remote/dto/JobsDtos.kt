package com.hermes.client.data.remote.dto

import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Hermes cron jobs (`gateway/platforms/api_server.py`, `cron/jobs.py`). Shape verified live
 * against the deployment (create → inspect → delete of a paused probe job).
 *
 * Notes from the server source (confirmed by Hermes reading its own code):
 *  - POST returns 200 (not 201); errors are 400 (validation) / 424 (scheduler) / 501 (no cron).
 *  - PATCH whitelist is name/schedule/prompt/deliver/skills/skill/repeat/enabled — **not**
 *    `paused`; pause/resume must go through POST /api/jobs/{id}/pause|resume so that
 *    paused_at/paused_reason are recorded correctly.
 *  - GET /api/jobs returns only *enabled* jobs unless `include_disabled=true`; jobs whose
 *    `repeat` count is exhausted are stored disabled, so reconciliation must always pass it.
 *  - `deliver` is a free-form string resolved at fire time ("local", "origin", "all",
 *    "platform", "platform:chat_id", comma lists, plugin platforms such as "ntfy:topic").
 *  - The full result text is NOT part of the job record — it lives in the run session
 *    (`cron_{job_id}_{timestamp}`, source="cron") and in server-side output files.
 */
data class JobsListResponse(
    @SerializedName("jobs") val jobs: List<CronJobDto> = emptyList(),
)

data class CronJobDto(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("prompt") val prompt: String = "",
    @SerializedName("skills") val skills: List<String> = emptyList(),
    /** True = script-only job: stdout is delivered verbatim, NO session/transcript is created. */
    @SerializedName("no_agent") val noAgent: Boolean = false,
    @SerializedName("schedule") val schedule: ScheduleDto? = null,
    @SerializedName("schedule_display") val scheduleDisplay: String? = null,
    @SerializedName("repeat") val repeat: RepeatDto? = null,
    @SerializedName("enabled") val enabled: Boolean = true,
    /** "active" | "paused" | … (server states; we only special-case "paused"). */
    @SerializedName("state") val state: String? = null,
    @SerializedName("paused_reason") val pausedReason: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("next_run_at") val nextRunAt: String? = null,
    @SerializedName("last_run_at") val lastRunAt: String? = null,
    /** ok | error | delivery_failed | blocked_config | null (never ran). */
    @SerializedName("last_status") val lastStatus: String? = null,
    @SerializedName("last_error") val lastError: String? = null,
    @SerializedName("last_delivery_error") val lastDeliveryError: String? = null,
    @SerializedName("failure_streak") val failureStreak: Int = 0,
    @SerializedName("deliver") val deliver: String? = null,
    /** Present only on list responses: the newest row of the executions ledger for this job. */
    @SerializedName("latest_execution") val latestExecution: ExecutionDto? = null,
)

data class ScheduleDto(
    @SerializedName("kind") val kind: String? = null, // "cron" | "interval" | "once" | …
    @SerializedName("expr") val expr: String? = null,
    @SerializedName("display") val display: String? = null,
)

data class RepeatDto(
    @SerializedName("times") val times: Int? = null,
    @SerializedName("completed") val completed: Int = 0,
)

/**
 * One claimed cron fire from `~/.hermes/cron/executions.db`. `id` is a fresh uuid per fire —
 * the correct de-duplication key for "a new run finished" (comparing `last_run_at` strings can
 * both miss re-fires and double-count edits).
 */
data class ExecutionDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("job_id") val jobId: String? = null,
    /** claimed | running | completed | failed | unknown. */
    @SerializedName("status") val status: String? = null,
    @SerializedName("claimed_at") val claimedAt: String? = null,
    @SerializedName("started_at") val startedAt: String? = null,
    @SerializedName("finished_at") val finishedAt: String? = null,
    @SerializedName("error") val error: String? = null,
    @SerializedName("delivery_outcome") val deliveryOutcome: String? = null,
)

/** POST /api/jobs */
data class JobCreateRequest(
    @SerializedName("name") val name: String,
    @SerializedName("schedule") val schedule: String,
    @SerializedName("prompt") val prompt: String,
    @SerializedName("deliver") val deliver: String? = null,
    @SerializedName("skills") val skills: List<String>? = null,
    @SerializedName("repeat") val repeat: Int? = null,
    @SerializedName("paused") val paused: Boolean? = null,
    @SerializedName("paused_reason") val pausedReason: String? = null,
)

/** PATCH /api/jobs/{id} — server whitelist only; `paused` is deliberately absent. */
data class JobPatchRequest(
    @SerializedName("name") val name: String? = null,
    @SerializedName("schedule") val schedule: String? = null,
    @SerializedName("prompt") val prompt: String? = null,
    @SerializedName("deliver") val deliver: String? = null,
    @SerializedName("skills") val skills: List<String>? = null,
    @SerializedName("repeat") val repeat: Int? = null,
    @SerializedName("enabled") val enabled: Boolean? = null,
)

/** POST /api/jobs/{id}/run — body is optional (transient per-run context). */
data class JobRunRequest(
    @SerializedName("prompt") val prompt: String? = null,
)

/**
 * Server timestamps look like `2026-10-12T10:00:22.138089+08:00` (microsecond precision, offset
 * included). Parse leniently — a value we cannot understand must never crash a sync.
 */
fun String?.toEpochMillisOrNull(): Long? = when {
    this.isNullOrBlank() -> null
    else -> try {
        OffsetDateTime.parse(this).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            Instant.parse(this).toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
