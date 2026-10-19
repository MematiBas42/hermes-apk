package com.hermes.client.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.hermes.client.BuildConfig
import com.hermes.client.R
import com.hermes.client.data.prefs.ThemeMode

@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { viewModel.setAssistantAvatar(it) } }

    SettingsScreen(
        baseUrl = settings.baseUrl,
        apiKey = settings.apiKey,
        filesBaseUrl = settings.filesBaseUrl.ifBlank { settings.effectiveFilesBaseUrl },
        jobsPushUrl = settings.jobsPushUrl,
        jobsPushTopic = settings.jobsPushTopic,
        jobsPushToken = settings.jobsPushToken,
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor,
        appLanguage = settings.appLanguage,
        systemInstructions = settings.systemInstructions,
        toolProgress = settings.toolProgress,
        assistantName = settings.assistantName,
        assistantAvatarPath = settings.assistantAvatarPath,
        test = test,
        onBack = onBack,
        onSaveConnection = viewModel::saveConnection,
        onTestConnection = viewModel::testConnection,
        onSaveInstructions = viewModel::saveSystemInstructions,
        onThemeMode = viewModel::setThemeMode,
        onDynamicColor = viewModel::setDynamicColor,
        onAppLanguage = viewModel::setAppLanguage,
        onToolProgress = viewModel::setToolProgress,
        onAssistantName = viewModel::setAssistantName,
        onPickAvatar = { avatarPicker.launch("image/*") },
        onClearAvatar = viewModel::clearAssistantAvatar,
        onSaveFilesBaseUrl = viewModel::setFilesBaseUrl,
        onSaveJobsPush = viewModel::saveJobsPush,
        onClearTest = viewModel::clearTest,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    baseUrl: String,
    apiKey: String,
    filesBaseUrl: String,
    jobsPushUrl: String,
    jobsPushTopic: String,
    jobsPushToken: String,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    appLanguage: String,
    systemInstructions: String,
    toolProgress: Boolean,
    assistantName: String,
    assistantAvatarPath: String,
    test: ConnectionTest,
    onBack: () -> Unit,
    onSaveConnection: (String, String) -> Unit,
    onTestConnection: (String, String) -> Unit,
    onSaveInstructions: (String) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onAppLanguage: (String) -> Unit,
    onToolProgress: (Boolean) -> Unit,
    onAssistantName: (String) -> Unit,
    onPickAvatar: () -> Unit,
    onClearAvatar: () -> Unit,
    onSaveFilesBaseUrl: (String) -> Unit,
    onSaveJobsPush: (String, String, String) -> Unit,
    onClearTest: () -> Unit,
) {
    var baseUrlField by remember(baseUrl) { mutableStateOf(baseUrl) }
    var apiKeyField by remember(apiKey) { mutableStateOf(apiKey) }
    var filesUrlField by remember(filesBaseUrl) { mutableStateOf(filesBaseUrl) }
    var jobsPushUrlField by remember(jobsPushUrl) { mutableStateOf(jobsPushUrl) }
    var jobsPushTopicField by remember(jobsPushTopic) { mutableStateOf(jobsPushTopic) }
    var jobsPushTokenField by remember(jobsPushToken) { mutableStateOf(jobsPushToken) }
    var instructionsField by remember(systemInstructions) { mutableStateOf(systemInstructions) }
    var assistantNameField by remember(assistantName) { mutableStateOf(assistantName) }
    var keyVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(stringResource(R.string.section_server))
            OutlinedTextField(
                value = baseUrlField,
                onValueChange = { baseUrlField = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.base_url)) },
                placeholder = { Text(stringResource(R.string.base_url_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = apiKeyField,
                onValueChange = { apiKeyField = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.api_key)) },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = null,
                        )
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onSaveConnection(baseUrlField, apiKeyField); onClearTest() },
                ) { Text(stringResource(R.string.save)) }
                Button(
                    onClick = { onTestConnection(baseUrlField, apiKeyField) },
                ) {
                    if (test is ConnectionTest.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.test_connection))
                }
            }
            when (test) {
                is ConnectionTest.Success -> Column {
                    val modelsStr = test.models.joinToString().ifBlank {
                        stringResource(R.string.conn_success_no_models)
                    }
                    Text(
                        text = stringResource(R.string.conn_success, modelsStr),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (test.insecureUrl) {
                        Text(
                            text = stringResource(R.string.insecure_http_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                is ConnectionTest.Failure -> Text(
                    text = stringResource(R.string.conn_failure, test.message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }

            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = filesUrlField,
                onValueChange = { filesUrlField = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.files_base_url)) },
                placeholder = { Text("https://example.com/hermes-files") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            )
            Text(
                text = stringResource(R.string.files_base_url_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onSaveFilesBaseUrl(filesUrlField) },
                enabled = filesUrlField != filesBaseUrl,
            ) { Text(stringResource(R.string.save_files_url)) }

            Spacer(Modifier.height(6.dp))
            SectionTitle(stringResource(R.string.section_jobs_push))
            OutlinedTextField(
                value = jobsPushUrlField,
                onValueChange = { jobsPushUrlField = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ntfy_url)) },
                placeholder = { Text("https://ntfy.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = jobsPushTopicField,
                onValueChange = { jobsPushTopicField = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ntfy_topic)) },
                placeholder = { Text("hermes-jobs-xxx") },
                singleLine = true,
            )
            OutlinedTextField(
                value = jobsPushTokenField,
                onValueChange = { jobsPushTokenField = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ntfy_token)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                text = stringResource(R.string.ntfy_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    onSaveJobsPush(jobsPushUrlField, jobsPushTopicField, jobsPushTokenField)
                },
                enabled = jobsPushUrlField != jobsPushUrl || jobsPushTopicField != jobsPushTopic ||
                    jobsPushTokenField != jobsPushToken,
            ) { Text(stringResource(R.string.save_jobs_push)) }

            Spacer(Modifier.height(6.dp))
            SectionTitle(stringResource(R.string.section_appearance))
            Text(
                text = stringResource(R.string.theme),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onThemeMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                                    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                    ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                },
                            )
                        },
                    )
                }
            }
            SettingSwitch(
                title = stringResource(R.string.dynamic_color),
                subtitle = stringResource(R.string.dynamic_color_desc),
                checked = dynamicColor,
                onChange = onDynamicColor,
            )

            Spacer(Modifier.height(6.dp))
            SectionTitle(stringResource(R.string.section_language))
            var languageExpanded by remember { mutableStateOf(false) }
            val languageOptions = listOf(
                "system" to stringResource(R.string.lang_system),
                "zh-CN" to stringResource(R.string.lang_zh),
                "en" to stringResource(R.string.lang_en),
                "tr" to stringResource(R.string.lang_tr),
            )
            // Source of truth is AppCompat, not the DataStore mirror: the user can also change the
            // language from Android Settings > Apps > Hermes > Language (locales_config.xml).
            val effectiveLanguage = remember(appLanguage) { currentAppLanguageTag() }
            val currentLanguageLabel = languageOptions.firstOrNull { it.first == effectiveLanguage }?.second
                ?: stringResource(R.string.lang_system)

            ExposedDropdownMenuBox(
                expanded = languageExpanded,
                onExpandedChange = { languageExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = currentLanguageLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = languageExpanded,
                    onDismissRequest = { languageExpanded = false },
                ) {
                    languageOptions.forEach { (tag, label) ->
                        val isSelected = effectiveLanguage == tag
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = label,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else null,
                            onClick = {
                                onAppLanguage(tag)
                                languageExpanded = false
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            SectionTitle(stringResource(R.string.section_chat))
            SettingSwitch(
                title = stringResource(R.string.tool_progress),
                subtitle = stringResource(R.string.tool_progress_desc),
                checked = toolProgress,
                onChange = onToolProgress,
            )
            OutlinedTextField(
                value = instructionsField,
                onValueChange = { instructionsField = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.system_instructions)) },
                placeholder = { Text(stringResource(R.string.system_instructions_desc)) },
                minLines = 2,
                maxLines = 6,
            )
            Button(
                onClick = { onSaveInstructions(instructionsField) },
                enabled = instructionsField != systemInstructions,
            ) { Text(stringResource(R.string.save_instructions)) }

            Spacer(Modifier.height(6.dp))
            SectionTitle(stringResource(R.string.section_assistant))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    if (assistantAvatarPath.isNotBlank()) {
                        AsyncImage(
                            model = java.io.File(assistantAvatarPath),
                            contentDescription = null,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Button(onClick = onPickAvatar) { Text(stringResource(R.string.choose_avatar)) }
                    TextButton(
                        onClick = onClearAvatar,
                        enabled = assistantAvatarPath.isNotBlank(),
                    ) { Text(stringResource(R.string.reset_default)) }
                }
            }
            OutlinedTextField(
                value = assistantNameField,
                onValueChange = { assistantNameField = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.assistant_name)) },
                placeholder = { Text("Hermes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
            Button(
                onClick = { onAssistantName(assistantNameField) },
                enabled = assistantNameField.trim() != assistantName.trim(),
            ) { Text(stringResource(R.string.save_assistant_name)) }

            Spacer(Modifier.height(6.dp))
            SectionTitle(stringResource(R.string.section_about))
            Text(
                text = "Hermes Android v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.about_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Maps the active per-app locale to one of the dropdown tags ("system" when none is set). */
private fun currentAppLanguageTag(): String {
    val locales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return "system"
    return when (locales[0]?.language) {
        "zh" -> "zh-CN"
        "en" -> "en"
        "tr" -> "tr"
        else -> "system"
    }
}
