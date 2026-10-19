package com.hermes.client.data.repository

import com.hermes.client.data.remote.HermesApi
import com.hermes.client.data.remote.dto.ServerMessage

/**
 * Pulls a session's **complete** transcript across pages, newest-first.
 *
 * Why this exists: the server caps `GET /api/sessions/{id}/messages` at 500 rows per response, and
 * (verified live, hermes `api_server.py`) a request with an explicit `limit` but no `order` is
 * served the **oldest** window (`pagination.order == "oldest"`). Hermes persists one row per tool
 * call/result, so an agent task easily exceeds 500 rows — e.g. a long multi-tool task has 536
 * rows. The old single-shot `limit=500` fetch therefore returned rows 1–500, and the
 * "server is authoritative" replace **deleted the newest 36 turns locally** (final answer
 * included). Symptom: newest messages disappearing after sync — only long, tool-heavy sessions hit the cap.
 *
 * Strategy: page with `order=latest` (offset 0 = newest window) until a page comes back short,
 * then re-assemble the pages in chronological order. If the transcript exceeds [MAX_PAGES] rows we
 * keep the newest window — for a chat UI losing ancient history is sad, losing the latest turn is
 * the bug.
 */
object SessionHistoryPager {

    const val PAGE_SIZE = 500

    /** Hard stop: 20k transcript rows is far beyond any real conversation; bounds time/memory. */
    const val MAX_PAGES = 40

    /**
     * @return the full transcript in chronological (oldest→newest) order; empty when the server
     *         has nothing for the session. Throws on transport errors — callers decide retry policy.
     */
    suspend fun fetchAll(api: HermesApi, sessionId: String): List<ServerMessage> {
        val pages = mutableListOf<List<ServerMessage>>()
        var pageSessionId = sessionId
        var offset = 0
        repeat(MAX_PAGES) {
            val page = api.sessionMessages(
                sessionId = pageSessionId,
                limit = PAGE_SIZE,
                offset = offset,
                order = "latest",
            )
            // The server resolves compression forks: the response's session_id is the *tip* of the
            // lineage, which may differ from the id we asked for. Page against the resolved id so
            // every page walks the same merged lineage.
            page.sessionId?.takeIf { it.isNotBlank() }?.let { pageSessionId = it }
            val rows = page.data
            if (rows.isEmpty()) return finish(pages)
            pages += rows
            // The server may cap the page below what we asked (it clamps at 500); page on the
            // *effective* limit so we never stop early while more rows exist.
            val effectiveLimit = page.pagination?.limit?.takeIf { it > 0 } ?: PAGE_SIZE
            val returned = page.pagination?.returned ?: rows.size
            if (page.pagination?.hasMore == false) return finish(pages)
            if (returned < effectiveLimit || rows.size < effectiveLimit) return finish(pages)
            offset += effectiveLimit
        }
        return finish(pages)
    }

    /**
     * Pages arrive newest-window-first; each page is already chronological inside. Reverse the
     * page order and concatenate. Concurrent appends during paging can shift an offset window and
     * re-serve a row in two pages, so dedupe by the server row id (nulls are always kept).
     */
    private fun finish(pages: List<List<ServerMessage>>): List<ServerMessage> {
        val seen = HashSet<Long>()
        val out = mutableListOf<ServerMessage>()
        for (page in pages.asReversed()) {
            for (row in page) {
                val id = row.id
                if (id == null || seen.add(id)) out += row
            }
        }
        return out
    }
}
