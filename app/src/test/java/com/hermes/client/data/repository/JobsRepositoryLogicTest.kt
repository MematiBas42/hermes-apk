package com.hermes.client.data.repository

import com.hermes.client.data.local.JobEntity
import com.hermes.client.data.remote.dto.CronJobDto
import com.hermes.client.data.remote.dto.ExecutionDto
import com.hermes.client.data.remote.dto.RepeatDto
import com.hermes.client.data.remote.dto.ScheduleDto
import com.hermes.client.data.remote.dto.toEpochMillisOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobsRepositoryLogicTest {

    private fun job(
        id: String = "abc123def456",
        execution: ExecutionDto? = null,
        lastStatus: String? = null,
    ) = CronJobDto(
        id = id,
        name = "日报",
        prompt = "p",
        schedule = ScheduleDto(kind = "cron", expr = "0 9 * * *", display = "every day at 9am"),
        scheduleDisplay = "every day at 9am",
        repeat = RepeatDto(times = null, completed = 3),
        enabled = true,
        state = "active",
        lastStatus = lastStatus,
        latestExecution = execution,
    )

    @Test
    fun `new terminal execution alerts`() {
        val dto = job(execution = ExecutionDto(id = "exec-2", status = "completed"))
        assertTrue(isNewTerminalExecution("exec-1", dto))
    }

    @Test
    fun `already seen execution id does not alert again`() {
        val dto = job(execution = ExecutionDto(id = "exec-1", status = "completed"))
        assertFalse(isNewTerminalExecution("exec-1", dto))
    }

    @Test
    fun `non terminal statuses are waited for`() {
        assertFalse(isNewTerminalExecution("exec-1", job(execution = ExecutionDto(id = "exec-2", status = "claimed"))))
        assertFalse(isNewTerminalExecution("exec-1", job(execution = ExecutionDto(id = "exec-2", status = "running"))))
    }

    @Test
    fun `failed and unknown executions alert`() {
        assertTrue(isNewTerminalExecution("exec-1", job(execution = ExecutionDto(id = "exec-2", status = "failed"))))
        assertTrue(isNewTerminalExecution("exec-1", job(execution = ExecutionDto(id = "exec-2", status = "unknown"))))
    }

    @Test
    fun `no execution or blank id never alerts`() {
        assertFalse(isNewTerminalExecution("exec-1", job(execution = null)))
        assertFalse(isNewTerminalExecution(null, job(execution = ExecutionDto(id = " ", status = "completed"))))
        // First ever sight of a completed job (previous null) is still news.
        assertTrue(isNewTerminalExecution(null, job(execution = ExecutionDto(id = "exec-9", status = "completed"))))
    }

    @Test
    fun `latest cron session is the lexicographic max of the job's prefix`() {
        val ids = listOf(
            "hm-other-session",
            "cron_abc123def456_20260101_090000",
            "cron_abc123def456_20261231_235959",
            "cron_abc123def456_20260601_120000",
            "cron_otherjob99_20261231_235959",
        )
        assertEquals("cron_abc123def456_20261231_235959", latestCronSessionId("abc123def456", ids))
        assertNull(latestCronSessionId("notthere", ids))
    }

    @Test
    fun `entity mapping keeps local-only memory across syncs`() {
        val existing = JobEntity(
            id = "abc123def456",
            name = "旧名",
            lastExecutionId = "exec-1",
            lastSessionId = "cron_abc123def456_20260101_090000",
        )
        val entity = toEntityInternal(job(), now = 1_000L, existing = existing)

        assertEquals("日报", entity.name) // server wins for mirrored fields
        assertEquals("exec-1", entity.lastExecutionId) // local memory survives
        assertEquals("cron_abc123def456_20260101_090000", entity.lastSessionId)
        assertEquals("every day at 9am", entity.scheduleDisplay)
        assertFalse(entity.paused)
    }

    @Test
    fun `microsecond offset timestamps parse and garbage stays null`() {
        val millis = "2026-10-12T10:00:22.138089+08:00".toEpochMillisOrNull()
        assertTrue(millis != null && millis > 0)
        // Same instant expressed in UTC parses to the same value.
        assertEquals(millis, "2026-10-12T02:00:22.138089Z".toEpochMillisOrNull())
        assertNull("not-a-date".toEpochMillisOrNull())
        assertNull("".toEpochMillisOrNull())
        assertNull(((null as String?)).toEpochMillisOrNull())
    }
}
