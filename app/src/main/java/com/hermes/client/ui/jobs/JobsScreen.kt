package com.hermes.client.ui.jobs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.model.CronJob
import com.hermes.client.ui.util.formatRelativeTime

@Composable
fun JobsRoute(
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    viewModel: JobsViewModel = hiltViewModel(),
) {
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(Unit) {
        viewModel.onVisible(true)
        onDispose { viewModel.onVisible(false) }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.openSession.collect { onOpenSession(it) }
    }

    JobsScreen(
        jobs = jobs,
        syncing = syncing,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSync = { viewModel.sync() },
        onCreate = { name, schedule, prompt, deliver ->
            viewModel.create(name, schedule, prompt, deliver)
        },
        onUpdate = { original, name, schedule, prompt, deliver ->
            viewModel.update(original, name, schedule, prompt, deliver)
        },
        onSetDeliverLocal = { viewModel.setDeliverLocal(it) },
        onOpenResult = { viewModel.openResult(it) },
        onRunNow = { viewModel.runNow(it) },
        onPause = { viewModel.pause(it) },
        onResume = { viewModel.resume(it) },
        onDelete = { viewModel.delete(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun JobsScreen(
    jobs: List<CronJob>,
    syncing: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onCreate: (String, String, String, String) -> Unit,
    onUpdate: (CronJob, String, String, String, String) -> Unit,
    onSetDeliverLocal: (CronJob) -> Unit,
    onOpenResult: (CronJob) -> Unit,
    onRunNow: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editorTarget by remember { mutableStateOf<CronJob?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<CronJob?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "定时任务",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onSync, enabled = !syncing) {
                        if (syncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Rounded.Sync, contentDescription = "同步任务列表")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editorTarget = null; editorOpen = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("新建任务") },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (jobs.isEmpty()) {
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "还没有定时任务",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "让 Hermes 按计划自动执行任务并推送结果",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        menuExpanded = menuTarget == job.id,
                        onMenuDismiss = { menuTarget = null },
                        onOpen = { onOpenResult(job) },
                        onLongPress = { menuTarget = job.id },
                        onOpenResult = { menuTarget = null; onOpenResult(job) },
                        onRunNow = { menuTarget = null; onRunNow(job.id) },
                        onTogglePause = {
                            menuTarget = null
                            if (job.paused) onResume(job.id) else onPause(job.id)
                        },
                        onSetDeliverLocal = { menuTarget = null; onSetDeliverLocal(job) },
                        onEdit = { menuTarget = null; editorTarget = job; editorOpen = true },
                        onDelete = { menuTarget = null; deleteTarget = job },
                    )
                }
            }
        }
    }

    if (editorOpen) {
        JobEditorDialog(
            initial = editorTarget,
            onDismiss = { editorOpen = false },
            onConfirm = { name, schedule, prompt, deliver ->
                editorOpen = false
                val target = editorTarget
                if (target == null) {
                    onCreate(name, schedule, prompt, deliver)
                } else {
                    onUpdate(target, name, schedule, prompt, deliver)
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除定时任务") },
            text = { Text("确定删除「${target.name}」？服务器上的任务与其计划将被移除。") },
            confirmButton = {
                TextButton(onClick = { onDelete(target.id); deleteTarget = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JobCard(
    job: CronJob,
    menuExpanded: Boolean,
    onMenuDismiss: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onOpenResult: () -> Unit,
    onRunNow: () -> Unit,
    onTogglePause: () -> Unit,
    onSetDeliverLocal: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = job.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusChip(job)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = job.schedule,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = lastRunLine(job),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.lastStatus == "error" || job.lastStatus == "delivery_failed") {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = when {
                        job.isFinished -> "已完成"
                        job.paused -> "已暂停"
                        job.nextRunAt > 0 -> "下次 " + formatRelativeTime(job.nextRunAt).removePrefix("刚刚")
                            .ifBlank { "即将执行" }
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Box {
                    IconButton(onClick = onLongPress) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
                        DropdownMenuItem(
                            text = { Text(if (job.noAgent) "查看执行记录" else "查看最新结果") },
                            leadingIcon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                            onClick = onOpenResult,
                        )
                        DropdownMenuItem(
                            text = { Text("立即运行") },
                            leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                            onClick = onRunNow,
                        )
                        DropdownMenuItem(
                            text = { Text(if (job.paused) "恢复" else "暂停") },
                            leadingIcon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                            onClick = onTogglePause,
                        )
                        if (job.deliver != "local") {
                            DropdownMenuItem(
                                text = { Text("投递改为本机（local）") },
                                leadingIcon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                                onClick = onSetDeliverLocal,
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("编辑") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = onEdit,
                        )
                        DropdownMenuItem(
                            text = { Text("删除") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            onClick = onDelete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(job: CronJob) {
    // 执行与投递是两回事：delivery_failed = 任务跑成功了、只是推送没送到，不该标红。
    val (label, container, content) = when {
        job.isFinished -> Triple("已结束", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        job.lastStatus == "error" || job.lastStatus == "blocked_config" ->
            Triple(if (job.lastStatus == "blocked_config") "配置受阻" else "执行失败", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        job.lastStatus == "delivery_failed" ->
            Triple("推送失败", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        job.paused -> Triple("已暂停", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Triple(if (job.noAgent) "脚本" else "运行中", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            disabledContainerColor = container,
            disabledLabelColor = content,
        ),
        border = null,
    )
}

private fun lastRunLine(job: CronJob): String {
    if (job.lastRunAt <= 0 && job.lastStatus == null) return "尚未执行过"
    val when_ = formatRelativeTime(job.lastRunAt).ifBlank { "刚刚" }
    return when (job.lastStatus) {
        "ok", null -> "上次执行 $when_ · 成功"
        "error" -> "上次执行 $when_ · 失败：${(job.lastError ?: "未知错误").take(80)}"
        // The run succeeded and the transcript is intact — only the push notification failed
        // (typically a retired delivery platform such as an unused 微信网关).
        "delivery_failed" -> "上次执行 $when_ · 执行成功，结果推送未送达（${job.deliver}）"
        "blocked_config" -> "配置校验未通过，未执行：${(job.lastError ?: "").take(60)}"
        else -> "上次执行 $when_ · ${job.lastStatus}"
    }
}

/** Quick schedule presets; Hermes accepts natural language, intervals and cron expressions. */
private val SCHEDULE_PRESETS = listOf(
    "every 1h" to "每小时",
    "every 6h" to "每 6 小时",
    "every day at 9:00" to "每天 9 点",
    "0 */2 * * *" to "每 2 小时 (cron)",
    "in 30m" to "30 分钟后一次",
)

@Composable
private fun JobEditorDialog(
    initial: CronJob?,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var schedule by remember { mutableStateOf(initial?.schedule ?: "every day at 9:00") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }
    var deliver by remember { mutableStateOf(initial?.deliver ?: "local") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建定时任务" else "编辑定时任务") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("任务名称") },
                )
                OutlinedTextField(
                    value = schedule,
                    onValueChange = { schedule = it },
                    singleLine = true,
                    label = { Text("计划（自然语言或 cron）") },
                    supportingText = { Text("例：every 2h / every day at 9am / 0 9 * * 1-5 / in 30m") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SCHEDULE_PRESETS.take(3).forEach { (value, label) ->
                        FilterChip(
                            selected = schedule == value,
                            onClick = { schedule = value },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("任务提示词") },
                    minLines = 3,
                    supportingText = { Text("自包含描述：定时任务在全新会话中运行，看不到当前对话") },
                )
                OutlinedTextField(
                    value = deliver,
                    onValueChange = { deliver = it },
                    singleLine = true,
                    label = { Text("结果投递 (deliver)") },
                    supportingText = {
                        Text("local 仅存档 / ntfy:主题 推送到 App / telegram 等。详见 Hermes 投递文档")
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank() || schedule.isBlank() || prompt.isBlank()) return@TextButton
                    onConfirm(name, schedule, prompt, deliver)
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
