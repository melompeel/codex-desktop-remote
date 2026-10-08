package com.alphapi.codexremote

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val viewModel: RemoteViewModel by viewModels()
    private val notificationRoute = MutableStateFlow<RemoteNotificationRoute?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationRoute.value = intent.remoteNotificationRoute()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF136F63),
                    onPrimary = Color.White,
                    primaryContainer = Color(0xFFD8EEE9),
                    secondary = Color(0xFF59645F),
                    secondaryContainer = Color(0xFFE2E8E4),
                    background = Color(0xFFF7F7F5),
                    surface = Color.White,
                    surfaceVariant = Color(0xFFE9ECE9),
                    surfaceContainer = Color(0xFFF0F2EF),
                    outline = Color(0xFF747A77),
                ),
            ) {
                val permission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }
                val state by viewModel.state.collectAsState()
                val route by notificationRoute.collectAsState()
                LaunchedEffect(state.configured) {
                    if (state.configured && Build.VERSION.SDK_INT >= 33) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F7F5)) {
                    if (state.configured) RemoteHome(
                        state,
                        viewModel.repository,
                        route,
                        onNotificationRouteConsumed = { consumed ->
                            notificationRoute.compareAndSet(consumed, null)
                        },
                    )
                    else PairingScreen(state, viewModel::pair)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationRoute.value = intent.remoteNotificationRoute()
    }
}

@Composable
private fun PairingScreen(state: RemoteState, pair: (String, String, String, String) -> Unit) {
    var url by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(Build.MODEL) }
    var connectionName by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().imePadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("连接 Codex Bridge", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            connectionName,
            { connectionName = it },
            label = { Text("连接名称（可选）") },
            placeholder = { Text("例如：我的电脑") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            url,
            { url = it },
            label = { Text("电脑地址") },
            placeholder = { Text("http://192.168.x.x:8766") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("六位配对码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(name, { name = it }, label = { Text("设备名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { pair(url, code, name, connectionName) },
            enabled = !state.loading && url.isNotBlank() && code.length == 6 && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.loading) CircularProgressIndicator(Modifier.height(20.dp)) else Text("连接")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RemoteHome(
    state: RemoteState,
    repository: RemoteRepository,
    notificationRoute: RemoteNotificationRoute? = null,
    onNotificationRouteConsumed: (RemoteNotificationRoute) -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var detailOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var showConnections by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showUpdates by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var confirmPush by remember { mutableStateOf(false) }
    var showNewTask by remember { mutableStateOf(false) }
    var settingsThreadId by remember { mutableStateOf<String?>(null) }
    var pendingFocus by remember { mutableStateOf<RemoteNotificationRoute?>(null) }
    LaunchedEffect(showNewTask, settingsThreadId) {
        if (showNewTask || settingsThreadId != null) repository.refreshModels()
    }
    var diffThreadId by remember { mutableStateOf<String?>(null) }
    var selectedProjectKey by rememberSaveable { mutableStateOf(OPEN_TASKS_KEY) }
    val groups = remember(state.tasks) { groupTasksByProject(state.tasks) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val updater = remember { AppUpdater(context.applicationContext) }
    val imeVisible = WindowInsets.isImeVisible
    val selected = state.tasks.firstOrNull { it.threadId == state.selectedThreadId }
    val selectedSyncing = selected?.threadId?.let { it in state.syncingThreadIds } == true
    val canWrite = selected?.ownerAvailable == true && state.writeSupported &&
        state.connected && state.ipcConnected
    val selectedProjectName = when (selectedProjectKey) {
        ProjectGroup.ALL_KEY -> "所有任务"
        OPEN_TASKS_KEY -> "最近任务"
        else -> groups.firstOrNull { it.key == selectedProjectKey }?.name ?: "所有任务"
    }

    LaunchedEffect(groups, selectedProjectKey) {
        if (selectedProjectKey != ProjectGroup.ALL_KEY && selectedProjectKey != OPEN_TASKS_KEY &&
            groups.none { it.key == selectedProjectKey }
        ) selectedProjectKey = ProjectGroup.ALL_KEY
    }

    LaunchedEffect(notificationRoute?.nonce) {
        val route = notificationRoute ?: return@LaunchedEffect
        when (route.kind) {
            RemoteNotificationKind.COMPLETION -> {
                repository.select(route.threadId)
                tab = 0
                detailOpen = true
            }
            RemoteNotificationKind.APPROVAL,
            RemoteNotificationKind.USER_INPUT,
            -> {
                pendingFocus = route
                tab = 1
                detailOpen = false
            }
        }
        onNotificationRouteConsumed(route)
    }

    LaunchedEffect(detailOpen, selected?.threadId, selected?.status) {
        val threadId = selected?.threadId
        if (detailOpen && threadId != null && threadId in state.completedReviewThreadIds) {
            repository.markTaskViewed(threadId)
        }
    }

    LaunchedEffect(Unit) {
        val result = updater.check()
        updateState = result
        if (result is UpdateState.Available || result is UpdateState.ReadyToInstall) showUpdates = true
    }

    LaunchedEffect(updateState) {
        while (updateState is UpdateState.Downloading) {
            delay(1_000)
            val current = withContext(Dispatchers.IO) { updater.currentDownloadState() }
            when {
                current == null -> updateState = UpdateState.Error("系统下载已中断，请重新下载")
                current !is UpdateState.Downloading -> updateState = current
            }
        }
    }

    RemoteBackNavigation(
        detailOpen = detailOpen,
        tab = tab,
        onCloseDetail = { detailOpen = false },
        onSelectTasks = { tab = 0 },
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = tab == 0 && !detailOpen,
        drawerContent = {
            ProjectDrawerContent(
                groups = groups,
                selectedKey = selectedProjectKey,
                activeServerUrl = state.serverUrl,
                onSelect = { key ->
                    selectedProjectKey = key
                    tab = 0
                    detailOpen = false
                    scope.launch { drawerState.close() }
                },
                onManageConnections = {
                    scope.launch { drawerState.close() }
                    showConnections = true
                },
                onAbout = {
                    scope.launch { drawerState.close() }
                    showAbout = true
                },
                useSystemRoute = state.connectionRouteMode == ConnectionRouteMode.SYSTEM,
                onUseSystemRouteChange = repository::setUseSystemRoute,
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (tab == 0 && detailOpen) {
                            IconButton(onClick = { detailOpen = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回任务列表")
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, "打开项目导航")
                            }
                        }
                    },
                    title = {
                        Column {
                            Text(
                                when {
                                    tab == 0 && detailOpen && selected != null -> selected.title
                                    tab == 0 -> selectedProjectName
                                    else -> "待处理"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val connectionStatus = connectionStatusPresentation(state)
                            val statusColor = if (connectionStatus.isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color(0xFF197344)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (selectedSyncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.8.dp,
                                        color = statusColor,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    connectionStatus.text + if (selectedSyncing) {
                                        " · 同步更新"
                                    } else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    actions = {
                        if (tab == 0 && detailOpen && selected != null) {
                            if (state.capabilities.diff) {
                                IconButton(onClick = {
                                    diffThreadId = selected.threadId
                                    repository.loadDiff(selected.threadId)
                                }) { Icon(Icons.Default.Difference, "查看完整 diff") }
                            }
                            IconButton(onClick = { confirmPush = true }, enabled = canWrite) {
                                Icon(Icons.Default.CloudUpload, "请求提交并推送")
                            }
                        } else if (tab == 0) {
                            IconButton(onClick = {
                                repository.clearTaskCreationError()
                                showNewTask = true
                            }) { Icon(Icons.Default.Add, "新建任务") }
                            IconButton(repository::refresh) { Icon(Icons.Default.Refresh, "刷新") }
                        } else {
                            IconButton(repository::refresh) { Icon(Icons.Default.Refresh, "刷新") }
                        }
                    },
                )
            },
            bottomBar = {
                if (!imeVisible) {
                    NavigationBar {
                        NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.AutoMirrored.Filled.List, "任务") }, label = { Text("任务") })
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1; detailOpen = false },
                            icon = {
                                if (state.pendingTaskCount > 0) Icon(Icons.Default.Warning, "有待处理事项")
                                else Icon(Icons.Default.Check, "无待处理事项")
                            },
                            label = { Text("待处理 ${state.pendingTaskCount}") },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                if (tab == 0) {
                    TasksPane(
                        state = state,
                        repository = repository,
                        groups = groups,
                        selectedProjectKey = selectedProjectKey,
                        detailOpen = detailOpen,
                        onOpenDetail = { detailOpen = true },
                        onOpenSettings = { settingsThreadId = it },
                    )
                } else {
                    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        PendingPane(
                            state = state,
                            repository = repository,
                            writeSupported = state.writeSupported && state.connected && state.ipcConnected && !state.loading,
                            focus = pendingFocus,
                            onOpenTask = { threadId ->
                                repository.select(threadId)
                                tab = 0
                                detailOpen = true
                            },
                        )
                    }
                }
            }
        }
    }
    if (showAbout) {
        AboutDialog(
            onDismiss = { showAbout = false },
            onCheckUpdates = {
                showAbout = false
                showUpdates = true
                updateState = UpdateState.Checking
                scope.launch { updateState = updater.check() }
            },
            onOpenAuthorEmail = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:wmelonpeel@gmail.com")),
                    )
                }.onFailure {
                    Toast.makeText(context, "没有找到可用的邮件应用", Toast.LENGTH_SHORT).show()
                }
            },
            onOpenSource = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/melompeel/codex-desktop-remote")),
                    )
                }.onFailure {
                    Toast.makeText(context, "没有找到可用的浏览器", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
    if (showConnections) {
        ConnectionManagerDialog(
            state = state,
            onDismiss = { showConnections = false },
            onSwitch = repository::switchConnection,
            onPair = { name, url, code, onSaved ->
                repository.pairConnection(name, url, code, onSaved)
            },
            onEdit = { connectionId, name, url, onSaved ->
                repository.editConnection(connectionId, name, url, onSaved)
            },
            onRemove = repository::removeConnection,
            onClearPairing = {
                showConnections = false
                confirmDisconnect = true
            },
        )
    }
    if (showUpdates) {
        AppUpdateDialog(
            state = updateState,
            onDismiss = { showUpdates = false },
            onRetry = {
                updateState = UpdateState.Checking
                scope.launch { updateState = updater.check() }
            },
            onDownload = { update ->
                updateState = try {
                    updater.enqueue(update)
                } catch (error: Exception) {
                    UpdateState.Error(error.message ?: "无法启动后台下载")
                }
            },
            onCancelDownload = { downloading ->
                updater.cancelDownload(downloading.downloadId)
                updateState = UpdateState.Checking
                scope.launch { updateState = updater.check() }
            },
            onInstall = { ready ->
                updater.install(ready.downloadId)
                showUpdates = false
            },
        )
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("断开并清除配对？") },
            text = { Text("本机保存的全部终端令牌都会被删除，需要各台电脑的新配对码才能再次连接。") },
            confirmButton = {
                Button(onClick = {
                    confirmDisconnect = false
                    repository.disconnect()
                }) { Text("断开") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDisconnect = false }) { Text("取消") }
            },
        )
    }
    if (confirmPush && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmPush = false },
            title = { Text("请求 Codex 提交并推送？") },
            text = { Text("Codex 会先检查改动和测试；发现失败、敏感信息或 upstream 问题时必须停止。") },
            confirmButton = {
                Button(onClick = {
                    confirmPush = false
                    repository.requestPush(selected.threadId)
                }) { Text("确认请求") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmPush = false }) { Text("取消") }
            },
        )
    }
    if (showNewTask) {
        NewTaskDialog(
            enabled = state.capabilities.newTask,
            groups = groups,
            preferredProjectKey = selectedProjectKey,
            models = state.models,
            creating = state.creatingTask,
            creationError = state.taskCreationError,
            onDismiss = {
                repository.clearTaskCreationError()
                showNewTask = false
            },
            onCreate = { repository.createTask(it) { showNewTask = false; detailOpen = true } },
        )
    }
    settingsThreadId?.let { threadId ->
        state.tasks.firstOrNull { it.threadId == threadId }?.let { task ->
            ThreadSettingsDialog(
                task = task,
                models = state.models,
                saving = threadId in state.settingsThreads,
                onDismiss = { settingsThreadId = null },
                onSave = { model, effort -> repository.updateSettings(threadId, model, effort); settingsThreadId = null },
            )
        }
    }
    diffThreadId?.let { threadId ->
        val task = state.tasks.firstOrNull { it.threadId == threadId }
        DiffViewerDialog(
            taskTitle = task?.title.orEmpty(),
            rawDiff = state.diffByThread[threadId],
            loading = threadId in state.loadingDiffThreads,
            onDismiss = { diffThreadId = null },
        )
    }
}

@Composable
internal fun RemoteBackNavigation(
    detailOpen: Boolean,
    tab: Int,
    onCloseDetail: () -> Unit,
    onSelectTasks: () -> Unit,
) {
    BackHandler(enabled = detailOpen, onBack = onCloseDetail)
    BackHandler(enabled = !detailOpen && tab != 0, onBack = onSelectTasks)
}

@Composable
internal fun AboutDialog(
    onDismiss: () -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenAuthorEmail: () -> Unit,
    onOpenSource: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column {
                    Text("Codex Desktop Remote", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "当前版本 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Button(onClick = onCheckUpdates, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.SystemUpdate, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("检查更新")
                }
                OutlinedButton(onClick = onOpenAuthorEmail, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Email, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("作者邮箱")
                        Text(
                            "wmelonpeel@gmail.com",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                OutlinedButton(onClick = onOpenSource, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Code, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("开源项目")
                        Text(
                            "github.com/melompeel/codex-desktop-remote",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

internal fun updateDialogConfirmLabel(state: UpdateState): String =
    if (state is UpdateState.Downloading) "后台下载" else "确认"

@Composable
private fun AppUpdateDialog(
    state: UpdateState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDownload: (AppUpdate) -> Unit,
    onCancelDownload: (UpdateState.Downloading) -> Unit,
    onInstall: (UpdateState.ReadyToInstall) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (state) {
                    is UpdateState.Available -> "发现新版本 ${state.update.version}"
                    is UpdateState.Downloading -> "正在下载 ${state.update.version}"
                    is UpdateState.ReadyToInstall -> "安装 ${state.update.version}"
                    UpdateState.Current -> "已经是最新版"
                    is UpdateState.Error -> "检查更新失败"
                    else -> "检查更新"
                },
            )
        },
        text = {
            when (state) {
                UpdateState.Idle, UpdateState.Checking -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.height(24.dp))
                    Text("正在从 GitHub Releases 获取版本信息...")
                }
                UpdateState.Current -> Text("当前版本 ${BuildConfig.VERSION_NAME}，暂时没有更高版本。")
                is UpdateState.Available -> Column(
                    Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("当前 ${BuildConfig.VERSION_NAME}  ·  最新 ${state.update.version}")
                    if (state.update.notes.isNotBlank()) {
                        HorizontalDivider()
                        Text(state.update.notes, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is UpdateState.Downloading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.height(24.dp))
                    Text("APK 由 Android 系统在后台下载。可以关闭此窗口，并在状态栏查看进度；完成后点击通知即可安装。")
                }
                is UpdateState.ReadyToInstall -> Text(
                    "APK 已下载。点击继续安装；如果 Android 先打开“允许安装未知应用”，允许后返回这里再点一次。",
                )
                is UpdateState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> Button(onClick = { onDownload(state.update) }) { Text("下载并安装") }
                is UpdateState.ReadyToInstall -> Button(onClick = { onInstall(state) }) { Text("继续安装") }
                is UpdateState.Error -> Button(onClick = onRetry) { Text("重试") }
                else -> TextButton(onClick = onDismiss) { Text(updateDialogConfirmLabel(state)) }
            }
        },
        dismissButton = {
            when (state) {
                is UpdateState.Downloading -> {
                    TextButton(onClick = { onCancelDownload(state) }) { Text("取消下载") }
                }
                is UpdateState.Available, is UpdateState.ReadyToInstall, is UpdateState.Error -> {
                    TextButton(onClick = onDismiss) { Text("稍后") }
                }
                else -> Unit
            }
        },
    )
}

@Composable
internal fun ConnectionManagerDialog(
    state: RemoteState,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onPair: (String, String, String, () -> Unit) -> Unit,
    onEdit: (String, String, String, () -> Unit) -> Unit,
    onRemove: (String) -> Unit,
    onClearPairing: () -> Unit,
) {
    var newUrl by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var editingConnectionId by remember { mutableStateOf<String?>(null) }
    val clearForm = {
        newName = ""
        newUrl = ""
        pairingCode = ""
        editingConnectionId = null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Codex 终端") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "新增另一台电脑需要输入它当前的六位配对码；切换已保存终端不需要重新配对。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.serverAddresses.forEach { address ->
                    val url = address.serverUrl
                    val connectionId = address.connectionId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.loading) { onSwitch(connectionId) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = connectionId == state.activeConnectionId,
                            onClick = { onSwitch(connectionId) },
                            enabled = !state.loading,
                            modifier = Modifier.semantics {
                                contentDescription = "切换到 ${address.name}"
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(address.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                url.removePrefix("http://").removePrefix("https://"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = {
                                editingConnectionId = connectionId
                                newName = address.name
                                newUrl = address.serverUrl
                                pairingCode = ""
                            },
                            enabled = !state.loading,
                        ) {
                            Icon(Icons.Default.Edit, "编辑终端 ${address.name}")
                        }
                        if (state.serverAddresses.size > 1) {
                            IconButton(
                                onClick = { onRemove(connectionId) },
                                enabled = !state.loading,
                            ) {
                                Icon(Icons.Default.Delete, "删除终端 ${address.name}")
                            }
                        }
                    }
                }
                Text(
                    if (editingConnectionId == null) {
                        "添加新终端"
                    } else {
                        "编辑已保存终端：只更新名称或 IP，继续使用原授权。"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("终端名称") },
                    placeholder = { Text("例如：办公室电脑") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("connection-name-input"),
                )
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    label = { Text(if (editingConnectionId == null) "新终端地址" else "终端地址") },
                    placeholder = { Text("http://192.168.x.x:8766") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("connection-url-input"),
                )
                if (editingConnectionId == null) {
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                        label = { Text("六位配对码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("connection-code-input"),
                    )
                }
                Button(
                    onClick = {
                        val editingId = editingConnectionId
                        if (editingId == null) {
                            onPair(newName, newUrl, pairingCode) {
                                clearForm()
                                onDismiss()
                            }
                        } else {
                            onEdit(editingId, newName, newUrl, clearForm)
                        }
                    },
                    enabled = !state.loading && newUrl.isNotBlank() &&
                        (editingConnectionId != null || pairingCode.length == 6),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (editingConnectionId == null) "配对并切换" else "保存地址")
                }
                if (editingConnectionId != null) {
                    TextButton(onClick = clearForm, modifier = Modifier.fillMaxWidth()) {
                        Text("取消编辑")
                    }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = onClearPairing) { Text("清除全部终端") }
        },
    )
}

@Composable
private fun TasksPane(
    state: RemoteState,
    repository: RemoteRepository,
    groups: List<ProjectGroup>,
    selectedProjectKey: String,
    detailOpen: Boolean,
    onOpenDetail: () -> Unit,
    onOpenSettings: (String) -> Unit,
) {
    val selected = state.tasks.firstOrNull { it.threadId == state.selectedThreadId }
    val canWrite = selected?.ownerAvailable == true && state.writeSupported &&
        state.connected && state.ipcConnected && !state.loading

    if (!detailOpen || selected == null) {
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            ProjectTaskList(
                groups = groups,
                selectedKey = selectedProjectKey,
                selectedThreadId = state.selectedThreadId,
                connected = state.connected,
                loading = state.taskListLoading,
                connectionError = state.error,
                canOpenOnDesktop = state.connected && state.ipcConnected &&
                    state.capabilities.taskActivation,
                onTaskClick = { task ->
                    repository.select(task.threadId)
                    onOpenDetail()
                },
                onOpenOnDesktop = { task -> repository.openOnDesktop(task.threadId) },
                onResyncTask = { task ->
                    repository.resyncTask(task.threadId)
                    onOpenDetail()
                },
            )
        }
        return
    }

    TaskConversationPane(
        task = selected,
        detail = state.taskDetail?.takeIf { it.threadId == selected.threadId },
        canWrite = canWrite,
        activating = selected.threadId in state.activatingThreads,
        syncing = selected.threadId in state.syncingThreadIds,
        loadingOlderHistory = selected.threadId in state.loadingOlderHistoryThreads,
        loadingAllHistory = selected.threadId in state.loadingAllHistoryThreads,
        historyPagesLoaded = state.historyLoadProgressByThread[selected.threadId] ?: 0,
        historyLoadFailed = selected.threadId in state.historyLoadErrorThreads,
        draft = state.draftsByThread[selected.threadId].orEmpty(),
        deliveryMode = state.deliveryByThread[selected.threadId] ?: defaultDeliveryFor(selected.status),
        queued = state.queueByThread[selected.threadId].orEmpty(),
        queueReady = state.queueHashByThread.containsKey(selected.threadId),
        attachments = state.attachmentsByThread[selected.threadId].orEmpty(),
        taskMediaById = state.taskMediaById,
        onLoadTaskMedia = { repository.loadTaskMedia(selected.threadId, it) },
        loadingTaskMediaIds = state.loadingTaskMediaIds,
        failedTaskMediaIds = state.failedTaskMediaIds,
        workspaceFiles = state.workspaceFiles,
        workspaceFilesLoading = state.workspaceFilesLoading,
        models = state.models,
        capabilities = state.capabilities,
        sending = selected.threadId in state.sendingThreads,
        stopping = selected.threadId in state.stoppingThreads,
        onDraftChange = { repository.updateDraft(selected.threadId, it) },
        onDeliveryChange = { repository.setDelivery(selected.threadId, it) },
        onSend = { repository.sendDraft(selected.threadId) },
        onStop = { repository.interrupt(selected.threadId) },
        onCancelQueued = { repository.cancelQueuedMessage(selected.threadId, it) },
        onOpenSettings = { onOpenSettings(selected.threadId) },
        onAttachmentsSelected = { repository.addAttachments(selected.threadId, it) },
        onRemoveAttachment = { repository.removeAttachment(selected.threadId, it) },
        onOpenResource = { repository.openTaskResource(selected.threadId, it) },
        onLoadWorkspaceFiles = { repository.loadWorkspaceFiles(selected.threadId, it) },
        onWorkspaceFileSelected = { repository.addWorkspaceAttachment(selected.threadId, it) },
        onLoadOlderHistory = { repository.loadOlderHistory(selected.threadId) },
        onLoadAllHistory = { repository.loadAllHistory(selected.threadId) },
    )
}

@Composable
private fun PendingPane(
    state: RemoteState,
    repository: RemoteRepository,
    writeSupported: Boolean,
    focus: RemoteNotificationRoute? = null,
    onOpenTask: (String) -> Unit,
) {
    var answering by remember { mutableStateOf<ApprovalDto?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val approvalThreadIds = state.approvals.map { it.threadId }
    val completedThreadIds = state.tasks
        .filter { it.threadId in state.completedReviewThreadIds }
        .sortedByDescending { it.updatedAt ?: 0L }
        .map { it.threadId }
    val pendingThreadIds = (approvalThreadIds + completedThreadIds).distinct()
    LaunchedEffect(focus?.nonce, pendingThreadIds) {
        val targetIndex = pendingThreadIds.indexOf(focus?.threadId)
        if (targetIndex >= 0) listState.animateScrollToItem(targetIndex)
    }
    LaunchedEffect(focus?.nonce, state.approvals) {
        if (focus?.kind != RemoteNotificationKind.USER_INPUT) return@LaunchedEffect
        answering = state.approvals.firstOrNull {
            it.threadId == focus.threadId && it.requestId == focus.requestId
        }
    }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (pendingThreadIds.isEmpty()) {
            item { Text("当前没有待处理事项", modifier = Modifier.padding(vertical = 24.dp)) }
        }
        items(pendingThreadIds, key = { it }) { threadId ->
            val task = state.tasks.firstOrNull { it.threadId == threadId }
            val approvals = state.approvals.filter { it.threadId == threadId }
            val userInputRequest = approvals.firstOrNull {
                it.method == "item/tool/requestUserInput"
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().clickable {
                    if (userInputRequest != null) answering = userInputRequest
                    else onOpenTask(threadId)
                },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(task?.title ?: threadId.take(12), fontWeight = FontWeight.Medium)
                    if (threadId in state.completedReviewThreadIds) {
                        Text(
                            if (task?.status == "failed") "运行失败待查看" else "已完成待查看",
                            color = if (task?.status == "failed") MaterialTheme.colorScheme.error else Color(0xFF197344),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        TextButton(onClick = { onOpenTask(threadId) }) { Text("查看结果") }
                    }
                    approvals.forEach { approval ->
                        if (threadId in state.completedReviewThreadIds || approval != approvals.first()) {
                            androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        }
                        Text(approvalLabel(approval.method), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        if (approval.method == "item/tool/requestUserInput") {
                            Button(
                                onClick = { answering = approval },
                                enabled = writeSupported,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("回答") }
                        } else {
                            ApprovalDecisionActions(
                                enabled = writeSupported,
                                onAccept = { repository.respondApproval(approval.requestId, "accept") },
                                onDecline = { repository.respondApproval(approval.requestId, "decline") },
                                onCancel = { repository.respondApproval(approval.requestId, "cancel") },
                            )
                        }
                    }
                }
            }
        }
    }
    answering?.let { request ->
        UserInputDialog(
            request = request,
            onDismiss = { answering = null },
            onSubmit = { answers ->
                repository.respondUserInput(request.requestId, answers)
                answering = null
            },
        )
    }
}

private fun approvalLabel(method: String): String = when (method) {
    "item/tool/requestUserInput" -> "Codex 需要你的回答"
    "item/commandExecution/requestApproval" -> "运行命令需要确认"
    "item/fileChange/requestApproval" -> "修改文件需要确认"
    "item/permissions/requestApproval" -> "权限请求需要确认"
    else -> "操作需要确认"
}

@Composable
internal fun ApprovalDecisionActions(
    enabled: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onAccept,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("仅本次允许") }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onDecline,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { Text("拒绝") }
            TextButton(
                onClick = onCancel,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) { Text("取消") }
        }
    }
}

private data class UserQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<String>,
    val secret: Boolean,
)

@Composable
internal fun UserInputDialog(
    request: ApprovalDto,
    onDismiss: () -> Unit,
    onSubmit: (Map<String, List<String>>) -> Unit,
) {
    val questions = remember(request.requestId) { request.userQuestions() }
    var values by remember(request.requestId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    val complete = questions.isNotEmpty() && questions.none { it.secret } &&
        questions.all { !values[it.id].isNullOrBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Codex 需要你的回答") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                questions.forEach { question ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(question.header, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(question.question, style = MaterialTheme.typography.bodyMedium)
                        if (question.secret) {
                            Text(
                                "敏感回答不会通过局域网传输，请在桌面处理",
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            question.options.forEach { option ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { values = values + (question.id to option) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = values[question.id] == option, onClick = { values = values + (question.id to option) })
                                    Text(option)
                                }
                            }
                            OutlinedTextField(
                                value = values[question.id].orEmpty(),
                                onValueChange = { values = values + (question.id to it) },
                                label = { Text("其他或修改") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (questions.isEmpty()) Text("这条请求没有可解析的问题，请回到桌面处理。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(values.mapValues { listOf(it.value.trim()) }) },
                enabled = complete,
            ) { Text("提交回答") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("返回") } },
    )
}

internal fun statusLabel(status: String): String = when (status) {
    "active", "inProgress" -> "进行中"
    "idle", "completed" -> "空闲"
    "failed" -> "失败"
    else -> status
}

private fun ApprovalDto.userQuestions(): List<UserQuestion> {
    val params = payload["params"] as? JsonObject ?: return emptyList()
    val questions = params["questions"] as? JsonArray ?: return emptyList()
    return questions.mapNotNull { element ->
        val value = element as? JsonObject ?: return@mapNotNull null
        val id = (value["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val options = (value["options"] as? JsonArray)
            ?.mapNotNull { option ->
                ((option as? JsonObject)?.get("label") as? JsonPrimitive)?.contentOrNull
            }
            .orEmpty()
        UserQuestion(
            id = id,
            header = (value["header"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            question = (value["question"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            options = options,
            secret = (value["isSecret"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }
}
