package com.hermes.client.data.remote

import com.hermes.client.data.remote.dto.Capabilities
import com.hermes.client.data.remote.dto.HealthResponse
import com.hermes.client.data.remote.dto.ModelsResponse
import com.hermes.client.data.remote.dto.RunCreatedDto
import com.hermes.client.data.remote.dto.RunRequest
import com.hermes.client.data.remote.dto.RunStatusDto
import com.hermes.client.data.remote.dto.SessionMessagesResponse
import com.hermes.client.data.remote.dto.ServerSessionsResponse
import com.hermes.client.data.remote.dto.SteerRequest
import com.hermes.client.data.remote.dto.UploadTokenDto
import com.hermes.client.data.remote.dto.JobsListResponse
import com.hermes.client.data.remote.dto.JobCreateRequest
import com.hermes.client.data.remote.dto.JobPatchRequest
import com.hermes.client.data.remote.dto.JobRunRequest
import com.google.gson.JsonElement
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Hermes OpenAI-compatible API.
 *
 * The real base URL is injected per request by [DynamicUrlInterceptor], so the paths here are
 * relative to whatever Base URL the user configured (it may contain a path prefix like
 * `https://host/hermes-api`).
 */
interface HermesApi {

    @GET("health")
    suspend fun health(): HealthResponse

    @GET("v1/models")
    suspend fun listModels(): ModelsResponse

    @GET("v1/capabilities")
    suspend fun capabilities(): Capabilities

    /**
     * Short-lived, upload-only credential for the companion file service. The master API key
     * must never appear inside run instructions (prompt injection would leak it, and a leaked
     * master key is remote code execution on the server); this scoped token is the only
     * credential the agent ever sees.
     */
    @POST("v1/uploads/token")
    suspend fun uploadToken(): UploadTokenDto

    @POST("v1/runs")
    suspend fun createRun(
        @Body body: RunRequest,
        @Header("Idempotency-Key") idempotencyKey: String? = null,
    ): RunCreatedDto

    @GET("v1/runs/{runId}")
    suspend fun runStatus(@Path("runId") runId: String): RunStatusDto

    @POST("v1/runs/{runId}/stop")
    suspend fun stopRun(@Path("runId") runId: String): JsonElement

    @POST("v1/runs/{runId}/steer")
    suspend fun steerRun(@Path("runId") runId: String, @Body body: SteerRequest): JsonElement

    /**
     * One page of a session transcript. Pages are chronological *within* themselves.
     * `order = "latest"` + increasing `offset` walks backwards in time from the newest row
     * (offset 0 = newest window); omitting `order` while passing `limit` makes the server serve
     * the *oldest* window instead — never do that for a full-history sync.
     */
    @GET("api/sessions/{sessionId}/messages")
    suspend fun sessionMessages(
        @Path("sessionId") sessionId: String,
        @Query("limit") limit: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("order") order: String = "latest",
    ): SessionMessagesResponse

    /** List every session known to the server (including ones created outside this app). */
    @GET("api/sessions")
    suspend fun listSessions(
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        /** Optional filter by session source, e.g. "cron" for scheduled-task runs. */
        @Query("source") source: String? = null,
    ): ServerSessionsResponse

    /** Delete a session on the server so it disappears for every client. */
    @DELETE("api/sessions/{sessionId}")
    suspend fun deleteSession(@Path("sessionId") sessionId: String): JsonElement

    // ------------------------------------------------------------------ cron jobs (/api/jobs)

    /**
     * All cron jobs. `includeDisabled` must be true for reconciliation: the server hides
     * disabled jobs by default and jobs whose repeat count is exhausted are stored disabled.
     */
    @GET("api/jobs")
    suspend fun listJobs(
        @Query("include_disabled") includeDisabled: Boolean = true,
    ): JobsListResponse

    /** Create a job. The server answers 200 (not 201) with `{"job": …}`. */
    @POST("api/jobs")
    suspend fun createJob(@Body body: JobCreateRequest): JsonElement

    /** Partial update — the server only accepts the whitelisted fields of [JobPatchRequest]. */
    @PATCH("api/jobs/{jobId}")
    suspend fun patchJob(
        @Path("jobId") jobId: String,
        @Body body: JobPatchRequest,
    ): JsonElement

    @DELETE("api/jobs/{jobId}")
    suspend fun deleteJob(@Path("jobId") jobId: String): JsonElement

    /** Pause records paused_at/paused_reason; PATCH has no `paused` field by design. */
    @POST("api/jobs/{jobId}/pause")
    suspend fun pauseJob(@Path("jobId") jobId: String): JsonElement

    @POST("api/jobs/{jobId}/resume")
    suspend fun resumeJob(@Path("jobId") jobId: String): JsonElement

    /** Trigger the job on the next scheduler tick (≤60 s); no run id is returned. */
    @POST("api/jobs/{jobId}/run")
    suspend fun runJob(
        @Path("jobId") jobId: String,
        @Body body: JobRunRequest = JobRunRequest(),
    ): JsonElement
}
