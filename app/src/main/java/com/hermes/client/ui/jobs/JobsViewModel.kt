package com.hermes.client.ui.jobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.JobsVisibility
import com.hermes.client.data.model.CronJob
import com.hermes.client.data.prefs.SettingsRepository
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.JobsRepository
import com.hermes.client.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class JobsViewModel @Inject constructor(
    private val jobsRepository: JobsRepository,
    private val sessionRepository: SessionRepository,
    private val chatRepository: ChatRepository,
    private val jobsVisibility: JobsVisibility,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val jobs: StateFlow<List<CronJob>> = jobsRepository.observeJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val configured: StateFlow<Boolean> = settingsRepository.settings
        .map { it.isConfigured }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _openSession = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openSession: SharedFlow<String> = _openSession.asSharedFlow()

    fun onVisible(visible: Boolean) {
        jobsVisibility.screenVisible = visible
        if (visible) sync(silent = true)
    }

    fun sync(silent: Boolean = false) {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                jobsRepository.sync(notify = false).fold(
                    onSuccess = { count ->
                        if (!silent) {
                            _events.tryEmit(if (count == 0) "服务器上还没有定时任务" else "已同步 $count 个任务")
                        }
                    },
                    onFailure = { error ->
                        if (!silent) _events.tryEmit("同步失败：${error.message ?: "网络错误"}")
                    },
                )
            } finally {
                _syncing.value = false
            }
        }
    }

    fun create(name: String, schedule: String, prompt: String, deliver: String) {
        viewModelScope.launch {
            jobsRepository.create(name, schedule, prompt, deliver).fold(
                onSuccess = { _events.tryEmit("已创建「${name.trim()}」，等待首次调度") },
                onFailure = { _events.tryEmit("创建失败：${it.message ?: "网络错误"}") },
            )
        }
    }

    /**
     * Partial edit: only fields that actually changed are PATCHed — the schedule field is
     * seeded with the job's display string, which must not be sent back verbatim.
     */
    fun update(original: CronJob, name: String, schedule: String, prompt: String, deliver: String) {
        viewModelScope.launch {
            jobsRepository.patch(
                id = original.id,
                name = name.ifBlank { original.name }.takeIf { it != original.name },
                schedule = schedule.takeIf { it != original.schedule },
                prompt = prompt.ifBlank { original.prompt }.takeIf { it != original.prompt },
                deliver = deliver.ifBlank { original.deliver }.takeIf { it != original.deliver },
            ).fold(
                onSuccess = { _events.tryEmit("已保存") },
                onFailure = { _events.tryEmit("保存失败：${it.message ?: "网络错误"}") },
            )
        }
    }

    /** One-tap repair for jobs still delivering to a retired platform (e.g. 微信网关). */
    fun setDeliverLocal(job: CronJob) {
        viewModelScope.launch {
            jobsRepository.setDeliverLocal(job.id).fold(
                onSuccess = { _events.tryEmit("「${job.name}」的结果投递已改为本机存档（local）") },
                onFailure = { _events.tryEmit("修改投递失败：${it.message ?: "网络错误"}") },
            )
        }
    }

    fun pause(id: String) {
        viewModelScope.launch {
            jobsRepository.pause(id).fold(
                onSuccess = { _events.tryEmit("已暂停"); sync(silent = true) },
                onFailure = { _events.tryEmit("暂停失败：${it.message ?: "网络错误"}") },
            )
        }
    }

    fun resume(id: String) {
        viewModelScope.launch {
            jobsRepository.resume(id).fold(
                onSuccess = { _events.tryEmit("已恢复"); sync(silent = true) },
                onFailure = { _events.tryEmit("恢复失败：${it.message ?: "网络错误"}") },
            )
        }
    }

    fun runNow(id: String) {
        viewModelScope.launch {
            jobsRepository.runNow(id).fold(
                onSuccess = {
                    _events.tryEmit("已触发，网关调度器将在 1 分钟内执行")
                    // The scheduler ticks every 60s; pick up the execution shortly after.
                    kotlinx.coroutines.delay(70_000)
                    sync(silent = true)
                },
                onFailure = { _events.tryEmit("触发失败：${it.message ?: "网络错误"}") },
            )
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            jobsRepository.delete(id).fold(
                onSuccess = { _events.tryEmit("已删除") },
                onFailure = { _events.tryEmit("删除失败：${it.message ?: "网络错误"}") },
            )
        }
    }

    /**
     * Open the transcript of the job's newest run. The session lives server-side only until the
     * first open, so make sure the local row exists and pull the history before navigating —
     * the chat screen otherwise shows an empty conversation until a manual refresh.
     * Script (`no_agent`) jobs never create a session; say so instead of a generic error.
     */
    fun openResult(job: CronJob) {
        viewModelScope.launch {
            val sessionId = job.lastSessionId ?: jobsRepository.latestSessionFor(job.id)
            if (sessionId == null) {
                _events.tryEmit(
                    if (job.noAgent) "脚本任务不产生对话记录，结果只走投递通道"
                    else "该任务还没有可查看的执行记录",
                )
                return@launch
            }
            sessionRepository.ensure(sessionId)
            runCatching { chatRepository.fetchHistory(sessionId) }
            _openSession.tryEmit(sessionId)
        }
    }
}
