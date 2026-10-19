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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.R
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
                        text = stringResource(R.string.jobs_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
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
                            Icon(Icons.Rounded.Sync, contentDescription = stringResource(R.string.sync_jobs))
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
                text = { Text(stringResource(R.string.new_job)) },
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
                        text = stringResource(R.string.no_jobs_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.no_jobs_desc),
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
            title = { Text(stringResource(R.string.delete_job_title)) },
            text = { Text(stringResource(R.string.delete_job_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = { onDelete(target.id); deleteTarget = null }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
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
                        job.isFinished -> stringResource(R.string.job_finished)
                        job.paused -> stringResource(R.string.job_paused)
                        job.nextRunAt > 0 -> stringResource(R.string.job_next_prefix) + " " + formatRelativeTime(job.nextRunAt).removePrefix(stringResource(R.string.time_just_now))
                            .ifBlank { stringResource(R.string.job_imminent) }
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
                            contentDescription = stringResource(R.string.more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
                        DropdownMenuItem(
                            text = { Text(if (job.noAgent) stringResource(R.string.job_view_records) else stringResource(R.string.job_view_latest)) },
                            leadingIcon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                            onClick = onOpenResult,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.job_run_now)) },
                            leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                            onClick = onRunNow,
                        )
                        DropdownMenuItem(
                            text = { Text(if (job.paused) stringResource(R.string.job_resume) else stringResource(R.string.job_pause)) },
                            leadingIcon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                            onClick = onTogglePause,
                        )
                        if (job.deliver != "local") {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.job_deliver_local)) },
                                leadingIcon = { Icon(Icons.Rounded.Schedule, contentDescription = null) },
                                onClick = onSetDeliverLocal,
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.edit)) },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = onEdit,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
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
    // Execution vs delivery: delivery_failed = run succeeded, only push failed
    val (label, container, content) = when {
        job.isFinished -> Triple(stringResource(R.string.job_ended), MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        job.lastStatus == "error" || job.lastStatus == "blocked_config" ->
            Triple(if (job.lastStatus == "blocked_config") stringResource(R.string.job_config_blocked) else stringResource(R.string.job_exec_failed), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        job.lastStatus == "delivery_failed" ->
            Triple(stringResource(R.string.job_delivery_failed), MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        job.paused -> Triple(stringResource(R.string.job_paused), MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Triple(if (job.noAgent) stringResource(R.string.job_script) else stringResource(R.string.job_running), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
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

@Composable
private fun lastRunLine(job: CronJob): String {
    if (job.lastRunAt <= 0 && job.lastStatus == null) return stringResource(R.string.job_not_run_yet)
    val when_ = formatRelativeTime(job.lastRunAt).ifBlank { stringResource(R.string.time_just_now) }
    val prefix = stringResource(R.string.job_last_run_prefix, when_) + " "
    return when (job.lastStatus) {
        "ok", null -> prefix + stringResource(R.string.job_status_success)
        "error" -> prefix + stringResource(R.string.job_status_failure, (job.lastError ?: "").take(80))
        "delivery_failed" -> prefix + stringResource(R.string.job_status_delivery_failed, job.deliver)
        "blocked_config" -> stringResource(R.string.job_status_blocked_config, (job.lastError ?: "").take(60))
        else -> prefix + job.lastStatus
    }
}

/** Quick schedule presets; Hermes accepts natural language, intervals and cron expressions. */
@Composable
private fun getSchedulePresets() = listOf(
    "every 1h" to stringResource(R.string.sched_every_1h),
    "every 6h" to stringResource(R.string.sched_every_6h),
    "every day at 9:00" to stringResource(R.string.sched_every_day_9am),
    "0 */2 * * *" to stringResource(R.string.sched_every_2h_cron),
    "in 30m" to stringResource(R.string.sched_in_30m),
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
    val presets = getSchedulePresets()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.create_job) else stringResource(R.string.edit_job)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.job_name)) },
                )
                OutlinedTextField(
                    value = schedule,
                    onValueChange = { schedule = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.job_schedule)) },
                    supportingText = { Text(stringResource(R.string.job_schedule_hint)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.take(3).forEach { (value, label) ->
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
                    label = { Text(stringResource(R.string.job_prompt)) },
                    minLines = 3,
                    supportingText = { Text(stringResource(R.string.job_prompt_hint)) },
                )
                OutlinedTextField(
                    value = deliver,
                    onValueChange = { deliver = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.job_deliver)) },
                    supportingText = {
                        Text(stringResource(R.string.job_deliver_hint))
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
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
