package com.alphapi.codexremote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LaptopWindows
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal const val OPEN_TASKS_KEY = "__open__"

@Composable
internal fun ProjectDrawerContent(
    groups: List<ProjectGroup>,
    selectedKey: String,
    activeServerUrl: String,
    onSelect: (String) -> Unit,
    onManageConnections: () -> Unit,
    onAbout: () -> Unit,
    useSystemRoute: Boolean = true,
    onUseSystemRouteChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val allTasks = groups.sumOf { it.tasks.size }
    val openTasks = groups.sumOf { group -> group.tasks.count { it.ownerAvailable } }
    ModalDrawerSheet(modifier.widthIn(max = 320.dp)) {
        Text(
            "Codex Remote",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
        )
        Text(
            activeServerUrl,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
        )
        NavigationDrawerItem(
            label = { DrawerLabel("最近任务", openTasks) },
            selected = selectedKey == OPEN_TASKS_KEY,
            onClick = { onSelect(OPEN_TASKS_KEY) },
            icon = { Icon(Icons.Default.LaptopWindows, null) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            label = { DrawerLabel("所有任务", allTasks) },
            selected = selectedKey == ProjectGroup.ALL_KEY,
            onClick = { onSelect(ProjectGroup.ALL_KEY) },
            icon = { Icon(Icons.Default.History, null) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        Text(
            "项目",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(groups, key = { it.key }) { group ->
                NavigationDrawerItem(
                    label = { DrawerLabel(group.name, group.tasks.size) },
                    selected = selectedKey == group.key,
                    onClick = { onSelect(group.key) },
                    icon = {
                        Icon(
                            if (selectedKey == group.key) Icons.Default.FolderOpen else Icons.Default.Folder,
                            null,
                        )
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("通过VPN/代理", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Switch(
                checked = useSystemRoute,
                onCheckedChange = onUseSystemRouteChange,
                modifier = Modifier.testTag("system-route-switch"),
            )
        }
        NavigationDrawerItem(
            label = { Text("Codex 终端") },
            selected = false,
            onClick = onManageConnections,
            icon = { Icon(Icons.Default.Link, null) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        NavigationDrawerItem(
            label = { Text("关于") },
            selected = false,
            onClick = onAbout,
            icon = { Icon(Icons.Default.Info, null) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DrawerLabel(label: String, count: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium)
    }
}

internal fun filteredProjectGroups(groups: List<ProjectGroup>, selectedKey: String): List<ProjectGroup> = when (selectedKey) {
    ProjectGroup.ALL_KEY -> groups
    OPEN_TASKS_KEY -> groups.mapNotNull { group ->
        group.tasks.filter { it.ownerAvailable }.takeIf(List<TaskDto>::isNotEmpty)?.let { group.copy(tasks = it) }
    }
    else -> groups.filter { it.key == selectedKey }
}

@Composable
internal fun ProjectTaskList(
    groups: List<ProjectGroup>,
    selectedKey: String,
    selectedThreadId: String?,
    connected: Boolean = true,
    loading: Boolean = false,
    connectionError: String? = null,
    onTaskClick: (TaskDto) -> Unit,
) {
    val visibleGroups = remember(groups, selectedKey) { filteredProjectGroups(groups, selectedKey) }
    if (visibleGroups.isEmpty()) {
        val presentation = emptyTaskListPresentation(connected, loading, connectionError)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (presentation.showLoading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp).testTag("task-list-loading"),
                        strokeWidth = 3.dp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(presentation.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(
                    presentation.message,
                    color = if (presentation.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag("task-list-empty-state"),
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        visibleGroups.forEach { group ->
            item("header:${group.key}") {
                Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) {
                    Text(group.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    group.cwd?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            items(group.tasks, key = { it.threadId }) { task ->
                Card(
                    onClick = { onTaskClick(task) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (task.threadId == selectedThreadId) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else MaterialTheme.colorScheme.surface,
                    ),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(task.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val availability = if (task.ownerAvailable) "桌面已打开" else "历史"
                            Text(
                                "$availability  ·  ${statusLabel(task.status)}  ·  待确认 ${task.pendingApprovals}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(
                            modifier = Modifier.size(28.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isActiveTaskStatus(task.status)) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp)
                                        .testTag("task-running:${task.threadId}"),
                                    strokeWidth = 2.5.dp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThreadSettingsDialog(
    task: TaskDto,
    models: List<ModelOptionDto>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var modelId by rememberSaveable(task.threadId) {
        mutableStateOf(task.settings?.model ?: models.firstOrNull { it.isDefault }?.id ?: models.firstOrNull()?.id.orEmpty())
    }
    val model = models.firstOrNull { it.id == modelId }
    var effort by rememberSaveable(task.threadId) {
        mutableStateOf(model?.let { compatibleReasoningEffort(it, task.settings?.effort) }.orEmpty())
    }
    LaunchedEffect(modelId) {
        model?.let { effort = compatibleReasoningEffort(it, effort).orEmpty() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型与推理强度") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SelectorButton(
                    title = "模型",
                    label = model?.displayName ?: "选择模型",
                    selectedValue = modelId,
                    options = models.map { it.id to it.displayName },
                    onSelect = { modelId = it },
                )
                SelectorButton(
                    title = "推理强度",
                    label = effortLabel(effort),
                    selectedValue = effort,
                    options = model?.supportedReasoningEfforts.orEmpty().map {
                        it.reasoningEffort to effortLabel(it.reasoningEffort)
                    },
                    onSelect = { effort = it },
                )
                model?.description?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(modelId, effort) }, enabled = !saving && modelId.isNotBlank() && effort.isNotBlank()) {
                Text(if (saving) "正在保存" else "保存")
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun NewTaskDialog(
    enabled: Boolean,
    groups: List<ProjectGroup>,
    preferredProjectKey: String,
    models: List<ModelOptionDto>,
    creating: Boolean,
    creationError: String? = null,
    onDismiss: () -> Unit,
    onCreate: (CreateTaskDraft) -> Unit,
) {
    val availableProjects = groups.filter { it.cwd != null }
    var mode by rememberSaveable { mutableStateOf("project") }
    var projectKey by rememberSaveable {
        mutableStateOf(preferredProjectKey.takeIf { key -> availableProjects.any { it.key == key } } ?: availableProjects.firstOrNull()?.key.orEmpty())
    }
    var prompt by rememberSaveable { mutableStateOf("") }
    var customCwd by rememberSaveable { mutableStateOf(availableProjects.firstOrNull()?.cwd.orEmpty()) }
    var modelId by rememberSaveable {
        mutableStateOf(models.firstOrNull { it.isDefault }?.id ?: models.firstOrNull()?.id.orEmpty())
    }
    val model = models.firstOrNull { it.id == modelId }
    var effort by rememberSaveable { mutableStateOf(model?.let { compatibleReasoningEffort(it, null) }.orEmpty()) }
    LaunchedEffect(modelId) { model?.let { effort = compatibleReasoningEffort(it, effort).orEmpty() } }
    val project = availableProjects.firstOrNull { it.key == projectKey }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 Codex 任务") },
        text = {
            if (!enabled) {
                Text("当前连接暂不支持远程新建任务。请先在电脑上新建并打开任务。")
            } else {
                Column(
                    Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SelectorButton(
                        title = "类型",
                        label = if (mode == "quick") "快速对话" else "项目任务",
                        selectedValue = mode,
                        options = listOf("project" to "项目任务", "quick" to "快速对话"),
                        onSelect = { mode = it },
                    )
                    if (mode == "project") {
                        SelectorButton(
                            title = "项目",
                            label = project?.name ?: "选择项目",
                            selectedValue = projectKey.orEmpty(),
                            options = availableProjects.map { it.key to it.name },
                            onSelect = { key ->
                                projectKey = key
                                customCwd = availableProjects.firstOrNull { it.key == key }?.cwd.orEmpty()
                            },
                        )
                        OutlinedTextField(
                            value = customCwd,
                            onValueChange = { customCwd = it },
                            label = { Text("电脑目录") },
                            singleLine = true,
                            supportingText = { Text("可填写尚未出现在项目列表中的目录") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("任务指令") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    creationError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    SelectorButton(
                        title = "模型",
                        label = model?.displayName ?: "选择模型",
                        selectedValue = modelId,
                        options = models.map { it.id to it.displayName },
                        onSelect = { modelId = it },
                    )
                    SelectorButton(
                        title = "推理强度",
                        label = effortLabel(effort),
                        selectedValue = effort,
                        options = model?.supportedReasoningEfforts.orEmpty().map {
                            it.reasoningEffort to effortLabel(it.reasoningEffort)
                        },
                        onSelect = { effort = it },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = enabled && !creating && (mode == "quick" || customCwd.isNotBlank()) && prompt.isNotBlank() && modelId.isNotBlank() && effort.isNotBlank(),
                onClick = {
                    onCreate(CreateTaskDraft(mode, projectKey.takeIf { mode == "project" }, customCwd.takeIf { mode == "project" }, prompt.trim(), modelId, effort))
                },
            ) { Text(if (creating) "正在创建" else "创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectorButton(
    title: String,
    label: String,
    selectedValue: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (options.isNotEmpty()) expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(title) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = options.isNotEmpty())
                .fillMaxWidth()
                .testTag("selector:$title"),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .heightIn(max = 320.dp)
                .testTag("selectorMenu:$title"),
        ) {
            options.forEach { (value, display) ->
                DropdownMenuItem(
                    text = { Text(display, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (value == selectedValue) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    onClick = { expanded = false; onSelect(value) },
                )
            }
        }
    }
}

internal fun effortLabel(value: String?): String = when (value) {
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    "xhigh" -> "XHigh"
    "max" -> "Max"
    "ultra" -> "Ultra"
    null, "" -> "选择推理强度"
    else -> value
}
