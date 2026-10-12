package com.hermes.client.data.repository

import com.google.gson.JsonPrimitive
import com.hermes.client.data.remote.HermesApi
import com.hermes.client.data.remote.dto.MessagesPagination
import com.hermes.client.data.remote.dto.ServerMessage
import com.hermes.client.data.remote.dto.SessionMessagesResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryPagerTest {

    private class FakeApi(
        var rows: List<ServerMessage>,
        var cap: Int = SessionHistoryPager.PAGE_SIZE,
        /** Rows appended after the first call — simulates a concurrent agent turn landing mid-sync. */
        var appendedMidSync: List<ServerMessage> = emptyList(),
    ) : HermesApi {
        val calls = mutableListOf<Triple<Int, Int, String>>()

        override suspend fun sessionMessages(
            sessionId: String,
            limit: Int,
            offset: Int,
            order: String,
        ): SessionMessagesResponse {
            if (calls.isNotEmpty() && appendedMidSync.isNotEmpty()) {
                rows = rows + appendedMidSync
                appendedMidSync = emptyList()
            }
            calls += Triple(limit, offset, order)
            // Server semantics (verified live): order=latest + offset pages BACK in time from the
            // newest row; each page is chronological inside itself.
            val from = (rows.size - offset).coerceAtLeast(0)
            val to = (from - cap).coerceAtLeast(0)
            val page = rows.subList(to, from).toList() // ascending ids within the window
            return SessionMessagesResponse(
                data = page,
                pagination = MessagesPagination(
                    limit = cap,
                    offset = offset,
                    order = order,
                    returned = page.size,
                ),
            )
        }

        // Unused endpoints for this test.
        override suspend fun health() = throw UnsupportedOperationException()
        override suspend fun listModels() = throw UnsupportedOperationException()
        override suspend fun capabilities() = throw UnsupportedOperationException()
        override suspend fun uploadToken() = throw UnsupportedOperationException()
        override suspend fun createRun(body: com.hermes.client.data.remote.dto.RunRequest, idempotencyKey: String?) = throw UnsupportedOperationException()
        override suspend fun runStatus(runId: String) = throw UnsupportedOperationException()
        override suspend fun stopRun(runId: String) = throw UnsupportedOperationException()
        override suspend fun steerRun(runId: String, body: com.hermes.client.data.remote.dto.SteerRequest) = throw UnsupportedOperationException()
        override suspend fun listSessions(limit: Int, offset: Int, source: String?) = throw UnsupportedOperationException()
        override suspend fun deleteSession(sessionId: String) = throw UnsupportedOperationException()
        override suspend fun listJobs(includeDisabled: Boolean) = throw UnsupportedOperationException()
        override suspend fun createJob(body: com.hermes.client.data.remote.dto.JobCreateRequest) = throw UnsupportedOperationException()
        override suspend fun patchJob(jobId: String, body: com.hermes.client.data.remote.dto.JobPatchRequest) = throw UnsupportedOperationException()
        override suspend fun deleteJob(jobId: String) = throw UnsupportedOperationException()
        override suspend fun pauseJob(jobId: String) = throw UnsupportedOperationException()
        override suspend fun resumeJob(jobId: String) = throw UnsupportedOperationException()
        override suspend fun runJob(jobId: String, body: com.hermes.client.data.remote.dto.JobRunRequest) = throw UnsupportedOperationException()
    }

    private fun row(id: Long) = ServerMessage(id = id, role = "assistant", content = JsonPrimitive("m$id"))

    @Test
    fun fetchesAllPagesAndReassemblesChronologically() = runBlocking {
        // 1234 rows: needs 3 pages of 500 (500 + 500 + 234).
        val rows = (1L..1234L).map(::row)
        val api = FakeApi(rows)

        val all = SessionHistoryPager.fetchAll(api, "s")

        assertEquals(1234, all.size)
        // Chronological: oldest first, newest last — the newest turn must survive the sync.
        assertEquals(1L, all.first().id)
        assertEquals(1234L, all.last().id)
        // Paged newest-first, never oldest-first without order.
        assertEquals(
            listOf(0, 500, 1000).map { Triple(SessionHistoryPager.PAGE_SIZE, it, "latest") },
            api.calls,
        )
    }

    @Test
    fun shortSessionNeedsASinglePage() = runBlocking {
        val api = FakeApi((1L..10L).map(::row))
        val all = SessionHistoryPager.fetchAll(api, "s")
        assertEquals(10, all.size)
        assertEquals(1, api.calls.size)
    }

    @Test
    fun emptySessionReturnsEmpty() = runBlocking {
        val api = FakeApi(emptyList())
        assertTrue(SessionHistoryPager.fetchAll(api, "s").isEmpty())
        assertEquals(1, api.calls.size)
    }

    @Test
    fun dedupesRowsReServedByAShiftingWindow() = runBlocking {
        // 600 rows; a concurrent append of 50 rows lands between page 1 and page 2, so the
        // offset-based window shifts and re-serves rows that page 1 already contained.
        val api = FakeApi(
            rows = (1L..600L).map(::row),
            appendedMidSync = (601L..650L).map(::row),
        )
        val all = SessionHistoryPager.fetchAll(api, "s")

        // No duplicates, ids are unique and the result stays chronological. Rows appended after
        // the offset-0 fetch simply aren't picked up this round (the next sync gets them).
        assertEquals(600, all.size)
        assertEquals(all.size, all.distinctBy { it.id }.size)
        val ids = all.mapNotNull { it.id }
        assertEquals(ids.sorted(), ids)
        assertEquals(600L, ids.last())
    }

    @Test
    fun respectsServerCappedPageSize() = runBlocking {
        // Server clamps pages below the requested size (e.g. 200): paging must use the effective
        // limit from `pagination.limit`, not the requested one, or it stops after one page.
        val rows = (1L..450L).map(::row)
        val api = FakeApi(rows).apply { cap = 200 }
        val all = SessionHistoryPager.fetchAll(api, "s")
        assertEquals(450, all.size)
        assertEquals(listOf(0, 200, 400), api.calls.map { it.second })
    }
}
