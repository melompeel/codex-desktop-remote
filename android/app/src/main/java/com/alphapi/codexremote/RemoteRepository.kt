package com.alphapi.codexremote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket

class RemoteRepository private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val store = CredentialStore(applicationContext)
    private val pendingReviewStore = PendingReviewStore(applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = mutableState.asStateFlow()
    @Volatile private var api: BridgeApi? = null
    private var stream: WebSocket? = null
    private val reconnectScheduler = ReconnectScheduler(scope) {
        if (api != null && !authorizationExpired) {
            openStream()
            refreshWithRecovery()
        }
    }
    private val connectionStatusGrace = ConnectionStatusGrace(scope) { connected ->
        update {
            it.copy(
                connected = connected,
                connectionEstablished = it.connectionEstablished || connected,
            )
        }
    }
    private var refreshCoordinator: ConflatedRefreshCoordinator? = null
    private var restoreJob: Job? = null
    @Volatile private var streamGeneration = 0
    @Volatile private var authorizationExpired = false
    private var followedThreadId: String? = null
    private var mediaCacheKey = ""
    private val mediaCache = TaskMediaCache(File(applicationContext.cacheDir, "task_media"))
    private val snapshotCache = TaskSnapshotCache(File(applicationContext.filesDir, "task_snapshots"))
    private val mediaJobs = mutableMapOf<String, Job>()
    private val historyJobs = mutableMapOf<String, Job>()
    private var activeConnection: StoredConnection? = null
    private var selectionGeneration = 0L
    private var networkReconnectJob: Job? = null
    private val networkMonitor = NetworkChangeMonitor(applicationContext, ::scheduleNetworkReconnect)

    init {
        networkMonitor.start()
    }

    fun restoreAndStart(startService: Boolean = true) {
        if (api != null) {
            if (startService) startListenerService()
            return
        }
        if (restoreJob?.isActive == true) return
        restoreJob = scope.launch {
            val saved = store.load() ?: return@launch
            val cachedTasks = snapshotCache.loadTasks(cacheKey(saved))
            update {
                it.copy(
                    configured = true,
                    taskListLoading = cachedTasks.isEmpty(),
                    serverUrl = saved.serverUrl,
                    activeConnectionId = saved.id,
                    connectionRouteMode = saved.routeMode,
                    serverAddresses = store.serverAddresses(),
                    tasks = cachedTasks,
                )
            }
            if (api == null) {
                runCatching {
                    configure(saved)
                    refresh()
                    openStream()
                }.onFailure(::recordError)
            }
            if (startService) startListenerService()
        }
    }

    fun pair(serverUrl: String, code: String, deviceName: String, connectionName: String = "") {
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                val normalized = BridgeEndpoint.normalize(serverUrl)
                val response = BridgeApi(normalized, null).pair(code.trim(), deviceName.trim())
                val label = connectionName.trim()
                val saved = if (label.isBlank()) {
                    StoredConnection(normalized, response.deviceId, response.token)
                } else {
                    StoredConnection(normalized, response.deviceId, response.token, label)
                }
                pendingReviewStore.clear()
                store.save(saved)
                configure(saved)
                openStream()
                startListenerService()
                refreshWithRecovery()
            }.onFailure { error -> update { it.copy(error = error.message) } }
            update { it.copy(loading = false) }
        }
    }

    fun refresh() {
        scheduleRefresh(0)
    }

    fun refreshModels() {
        val bridge = api ?: return
        scope.launch {
            runCatching { bridge.models(refresh = true) }
                .onSuccess { models -> if (api === bridge) update { it.copy(models = models) } }
                .onFailure(::recordError)
        }
    }

    fun pairConnection(
        name: String,
        serverUrl: String,
        code: String,
        onSaved: (() -> Unit)? = null,
    ) {
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                val normalized = BridgeEndpoint.normalize(serverUrl)
                val response = BridgeApi(normalized, null).pair(code.trim(), Build.MODEL)
                val saved = store.addConnection(
                    StoredConnection(
                        serverUrl = normalized,
                        deviceId = response.deviceId,
                        token = response.token,
                        name = name.trim(),
                    ),
                )
                if (onSaved != null) withContext(Dispatchers.Main.immediate) { onSaved() }
                activateConnection(saved)
            }.onFailure(::recordError)
            update { it.copy(loading = false) }
        }
    }

    fun editConnection(
        connectionId: String,
        name: String,
        serverUrl: String,
        onSaved: (() -> Unit)? = null,
    ) {
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                val normalized = BridgeEndpoint.normalize(serverUrl)
                val saved = requireNotNull(store.editConnection(connectionId, name, normalized)) {
                    "找不到已保存的终端"
                }
                update { it.copy(serverAddresses = store.serverAddresses()) }
                if (onSaved != null) withContext(Dispatchers.Main.immediate) { onSaved() }
                if (connectionId == mutableState.value.activeConnectionId) activateConnection(saved)
            }.onFailure(::recordError)
            update { it.copy(loading = false) }
        }
    }

    fun switchConnection(connectionId: String) {
        if (connectionId == mutableState.value.activeConnectionId) return
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                val saved = requireNotNull(store.selectConnection(connectionId)) { "找不到已保存的终端" }
                activateConnection(saved)
            }.onFailure(::recordError)
            update { it.copy(loading = false) }
        }
    }

    fun removeConnection(connectionId: String) {
        val snapshot = mutableState.value
        if (snapshot.serverAddresses.size <= 1) {
            update { it.copy(error = "至少需要保留一个终端") }
            return
        }
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                val saved = requireNotNull(store.removeConnection(connectionId)) { "当前没有已保存的终端" }
                if (connectionId == snapshot.activeConnectionId) activateConnection(saved)
                else update { it.copy(serverAddresses = store.serverAddresses()) }
            }.onFailure(::recordError)
            update { it.copy(loading = false) }
        }
    }

    fun setUseSystemRoute(useSystemRoute: Boolean) {
        val snapshot = mutableState.value
        val connectionId = snapshot.activeConnectionId.takeIf(String::isNotBlank) ?: return
        val requested = if (useSystemRoute) {
            ConnectionRouteMode.SYSTEM
        } else {
            ConnectionRouteMode.DIRECT_LAN
        }
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                val saved = requireNotNull(store.updateRouteMode(connectionId, requested)) {
                    "找不到当前终端"
                }
                activateConnection(saved)
            }.onFailure(::recordError)
            update { it.copy(loading = false) }
        }
    }

    fun select(threadId: String) {
        select(threadId, useCache = true)
    }

    fun resyncTask(threadId: String) {
        activeCacheKey()?.let { snapshotCache.clearDetail(it, threadId) }
        select(threadId, useCache = false)
    }

    fun openOnDesktop(threadId: String) {
        val bridge = api ?: run {
            update { it.copy(error = "Bridge 未连接") }
            return
        }
        if (!mutableState.value.capabilities.taskActivation) {
            update { it.copy(error = "当前 Bridge 不支持在桌面端打开会话") }
            return
        }
        update {
            it.copy(
                activatingThreads = it.activatingThreads + threadId,
                error = null,
            )
        }
        scope.launch {
            runCatching { bridge.activateTask(threadId) }
                .onSuccess { if (api === bridge) scheduleRefresh(0) }
                .onFailure(::recordError)
            update { it.copy(activatingThreads = it.activatingThreads - threadId) }
        }
    }

    private fun select(threadId: String, useCache: Boolean) {
        val snapshot = mutableState.value
        val openAction = taskOpenAction(
            task = snapshot.tasks.firstOrNull { it.threadId == threadId },
            capabilities = snapshot.capabilities,
            activationInProgress = threadId in snapshot.activatingThreads,
        )
        cancelMediaLoads()
        cancelHistoryLoads()
        markTaskViewed(threadId)
        val generation = if (
            openAction == TaskOpenAction.WAIT && snapshot.selectedThreadId == threadId
        ) {
            selectionGeneration
        } else {
            ++selectionGeneration
        }
        update {
            it.copy(
                selectedThreadId = threadId,
                taskDetail = null,
                loadingOlderHistoryThreads = emptySet(),
                loadingAllHistoryThreads = emptySet(),
                historyLoadProgressByThread = emptyMap(),
                historyLoadErrorThreads = emptySet(),
                syncingThreadIds = it.syncingThreadIds + threadId,
                taskMediaById = emptyMap(),
                loadingTaskMediaIds = emptySet(),
                failedTaskMediaIds = emptySet(),
                workspaceFiles = emptyList(),
                workspaceFilesLoading = false,
                activatingThreads = if (openAction == TaskOpenAction.ACTIVATE) {
                    it.activatingThreads + threadId
                } else {
                    it.activatingThreads
                },
            )
        }
        scope.launch {
            val cacheKey = activeCacheKey()
            cacheKey?.takeIf { useCache }?.let { key ->
                snapshotCache.loadDetail(key, threadId)?.let { cached ->
                    if (generation == selectionGeneration) {
                        update { state ->
                            if (state.selectedThreadId == threadId && state.taskDetail == null) {
                                state.copy(taskDetail = cached)
                            } else state
                        }
                    }
                }
            }
            if (openAction == TaskOpenAction.WAIT) {
                update { it.copy(syncingThreadIds = it.syncingThreadIds + threadId, error = null) }
                scheduleRefresh(500)
                return@launch
            }
            var keepSyncing = false
            try {
                val bridge = api
                val configurationFailure = if (bridge == null) {
                    IllegalStateException("Bridge is not configured")
                } else null
                val openResults = if (bridge != null) {
                    loadTaskWhileOpening(
                        openTask = {
                            if (openAction == TaskOpenAction.ACTIVATE) {
                                bridge.activateTask(threadId)
                            } else {
                                bridge.follow(threadId)
                                if (generation == selectionGeneration) followedThreadId = threadId
                            }
                        },
                        loadHistory = { bridge.taskDetail(threadId) },
                        onHistoryLoaded = { latest ->
                            if (api === bridge && generation == selectionGeneration) {
                                var merged: TaskDetailDto? = null
                                update { state ->
                                    if (state.selectedThreadId == threadId) {
                                        merged = mergeLatestHistory(state.taskDetail, latest)
                                        state.copy(taskDetail = merged)
                                    } else state
                                }
                                merged?.let { detail ->
                                    saveCachedDetail(detail)
                                }
                            }
                        },
                    )
                } else null
                openResults?.history?.exceptionOrNull()?.let { error ->
                    if (api === bridge && generation == selectionGeneration) {
                        val presentation = refreshFailurePresentation(error, mutableState.value)
                        if (presentation.shouldRetry && !presentation.isError) {
                            keepSyncing = true
                            update { state ->
                                if (state.selectedThreadId == threadId) {
                                    state.copy(
                                        syncingThreadIds = state.syncingThreadIds + threadId,
                                        error = null,
                                    )
                                } else state
                            }
                            scheduleRefresh(1_000)
                        } else {
                            recordError(error)
                        }
                    }
                }
                val openFailure = configurationFailure ?: openResults?.open?.exceptionOrNull()
                if (bridge != null) {
                    if (api === bridge) scheduleRefresh(0)
                    if (
                        openFailure != null &&
                        api === bridge &&
                        generation == selectionGeneration &&
                        mutableState.value.selectedThreadId == threadId
                    ) {
                        val presentation = refreshFailurePresentation(openFailure, mutableState.value)
                        when {
                            isAuthorizationFailure(openFailure) -> recordError(openFailure)
                            presentation.shouldRetry && !presentation.isError -> {
                                keepSyncing = true
                                update { it.copy(error = null) }
                                scheduleRefresh(1_000)
                            }
                            else -> update {
                                it.copy(
                                    error = if (openAction == TaskOpenAction.ACTIVATE) {
                                        "无法在电脑端载入此对话，当前仍可查看历史记录"
                                    } else {
                                        "暂未连接到桌面任务，当前仍可查看历史记录"
                                    },
                                )
                            }
                        }
                    }
                } else if (openFailure != null) {
                    recordError(openFailure)
                }
            } finally {
                update { state ->
                    if (generation == selectionGeneration || state.selectedThreadId != threadId) {
                        state.copy(
                            activatingThreads = state.activatingThreads - threadId,
                            syncingThreadIds = if (
                                keepSyncing &&
                                generation == selectionGeneration &&
                                state.selectedThreadId == threadId
                            ) {
                                state.syncingThreadIds + threadId
                            } else {
                                state.syncingThreadIds - threadId
                            },
                        )
                    } else state
                }
            }
        }
    }

    fun loadOlderHistory(threadId: String) {
        loadHistory(threadId, loadAll = false)
    }

    fun loadAllHistory(threadId: String) {
        loadHistory(threadId, loadAll = true)
    }

    private fun loadHistory(threadId: String, loadAll: Boolean) {
        val snapshot = mutableState.value
        val current = snapshot.taskDetail?.takeIf { it.threadId == threadId } ?: return
        current.historyCursor ?: return
        if (!current.hasMoreHistory || threadId in snapshot.loadingOlderHistoryThreads) return
        val bridge = api ?: return
        update {
            it.copy(
                loadingOlderHistoryThreads = it.loadingOlderHistoryThreads + threadId,
                loadingAllHistoryThreads = if (loadAll) it.loadingAllHistoryThreads + threadId else it.loadingAllHistoryThreads,
                historyLoadProgressByThread = it.historyLoadProgressByThread + (threadId to 0),
                historyLoadErrorThreads = it.historyLoadErrorThreads - threadId,
                error = null,
            )
        }
        val job = scope.launch {
            var pagesLoaded = 0
            var retryAfterFailure = false
            try {
                while (true) {
                    val state = mutableState.value
                    val detail = state.taskDetail?.takeIf {
                        state.selectedThreadId == threadId && it.threadId == threadId
                    } ?: break
                    val cursor = detail.historyCursor ?: break
                    if (!detail.hasMoreHistory || api !== bridge) break
                    val older = bridge.taskDetail(threadId, cursor)
                    var merged: TaskDetailDto? = null
                    update { latest ->
                        val visible = latest.taskDetail
                        if (latest.selectedThreadId != threadId || visible?.threadId != threadId || api !== bridge) {
                            latest
                        } else {
                            merged = mergeOlderHistory(visible, older)
                            latest.copy(
                                taskDetail = merged,
                                historyLoadProgressByThread = latest.historyLoadProgressByThread +
                                    (threadId to (pagesLoaded + 1)),
                            )
                        }
                    }
                    val result = merged ?: break
                    pagesLoaded += 1
                    saveCachedDetail(result)
                    if (!loadAll || !result.hasMoreHistory || result.historyCursor == null) break
                    check(result.historyCursor != cursor) { "历史记录游标没有继续前进" }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (api === bridge && mutableState.value.selectedThreadId == threadId) {
                    val presentation = refreshFailurePresentation(error, mutableState.value)
                    when {
                        isAuthorizationFailure(error) -> {
                            update {
                                it.copy(historyLoadErrorThreads = it.historyLoadErrorThreads + threadId)
                            }
                            recordError(error)
                        }
                        presentation.shouldRetry && !presentation.isError -> {
                            retryAfterFailure = true
                            update {
                                it.copy(
                                    historyLoadErrorThreads = it.historyLoadErrorThreads - threadId,
                                    error = null,
                                )
                            }
                        }
                        else -> update {
                            it.copy(
                                historyLoadErrorThreads = it.historyLoadErrorThreads + threadId,
                                error = null,
                            )
                        }
                    }
                }
            } finally {
                synchronized(historyJobs) {
                    if (historyJobs[threadId] == coroutineContext[Job]) historyJobs.remove(threadId)
                }
                update {
                    it.copy(
                        loadingOlderHistoryThreads = it.loadingOlderHistoryThreads - threadId,
                        loadingAllHistoryThreads = it.loadingAllHistoryThreads - threadId,
                    )
                }
                if (retryAfterFailure) {
                    scope.launch {
                        delay(1_500)
                        val latest = mutableState.value
                        val active = latest.tasks.firstOrNull { it.threadId == threadId }
                            ?.status
                            ?.lowercase() in setOf("active", "inprogress", "running")
                        if (
                            api === bridge &&
                            latest.connected &&
                            latest.selectedThreadId == threadId &&
                            active
                        ) {
                            loadHistory(threadId, loadAll)
                        }
                    }
                }
            }
        }
        synchronized(historyJobs) { historyJobs[threadId] = job }
    }

    fun markTaskViewed(threadId: String) {
        val remaining = pendingReviewStore.markViewed(threadId)
        update { it.copy(completedReviewThreadIds = remaining) }
    }

    fun openTaskResource(threadId: String, resource: TimelineResourceDto) {
        if (resource.resourceId in mutableState.value.downloadingResourceIds) return
        scope.launch {
            update {
                it.copy(
                    downloadingResourceIds = it.downloadingResourceIds + resource.resourceId,
                    error = null,
                )
            }
            runCatching {
                val bytes = requireApi().taskResource(threadId, resource.resourceId)
                val directory = File(applicationContext.cacheDir, "remote_files").apply { mkdirs() }
                val safeName = resource.name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                    .ifBlank { "download" }
                val file = File(directory, "${resource.resourceId.take(12)}-$safeName")
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(
                    applicationContext,
                    "${applicationContext.packageName}.files",
                    file,
                )
                val viewIntent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, resource.mimeType)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                applicationContext.startActivity(viewIntent)
            }.onFailure(::recordError)
            update {
                it.copy(downloadingResourceIds = it.downloadingResourceIds - resource.resourceId)
            }
        }
    }

    fun loadWorkspaceFiles(threadId: String, query: String = "") {
        scope.launch {
            update { it.copy(workspaceFilesLoading = true, error = null) }
            runCatching { requireApi().workspaceFiles(threadId, query) }
                .onSuccess { files -> update { it.copy(workspaceFiles = files) } }
                .onFailure(::recordError)
            update { it.copy(workspaceFilesLoading = false) }
        }
    }

    fun addWorkspaceAttachment(threadId: String, file: WorkspaceFileDto) {
        val existing = mutableState.value.attachmentsByThread[threadId].orEmpty()
        if (existing.size >= MAX_COMPOSER_ATTACHMENTS) {
            update { it.copy(error = "每条消息最多添加 $MAX_COMPOSER_ATTACHMENTS 个附件") }
            return
        }
        val pending = ComposerAttachment(
            localId = UUID.randomUUID().toString(),
            uri = "",
            name = file.name,
            mimeType = file.mimeType,
            size = file.size,
            uploadState = AttachmentUploadState.UPLOADING,
        )
        update { state ->
            state.copy(
                attachmentsByThread = state.attachmentsByThread +
                    (threadId to (state.attachmentsByThread[threadId].orEmpty() + pending)),
                error = null,
            )
        }
        scope.launch {
            runCatching { requireApi().importWorkspaceAttachment(threadId, file.relativePath) }
                .onSuccess { uploaded ->
                    replaceAttachment(threadId, pending.localId) {
                        it.copy(
                            uploadState = AttachmentUploadState.READY,
                            attachmentId = uploaded.attachmentId,
                        )
                    }
                }
                .onFailure { error ->
                    replaceAttachment(threadId, pending.localId) {
                        it.copy(
                            uploadState = AttachmentUploadState.FAILED,
                            error = error.message ?: "电脑文件读取失败",
                        )
                    }
                }
        }
    }

    fun sendMessage(threadId: String, text: String, onSent: (() -> Unit)? = null) = action(onSent) {
        requireApi().sendMessage(threadId, text)
    }

    fun updateDraft(threadId: String, value: String) {
        update { state -> state.copy(draftsByThread = state.draftsByThread + (threadId to value)) }
    }

    fun setDelivery(threadId: String, delivery: DeliveryMode) {
        update { state -> state.copy(deliveryByThread = state.deliveryByThread + (threadId to delivery)) }
    }

    fun sendDraft(threadId: String) {
        val snapshot = mutableState.value
        val text = snapshot.draftsByThread[threadId].orEmpty().trim()
        val attachments = snapshot.attachmentsByThread[threadId].orEmpty()
        val attachmentIds = attachments.mapNotNull { it.attachmentId }
        if ((text.isEmpty() && attachmentIds.isEmpty()) || attachments.any { it.uploadState != AttachmentUploadState.READY }) return
        val task = snapshot.tasks.firstOrNull { it.threadId == threadId } ?: return
        val requestedDelivery = snapshot.deliveryByThread[threadId] ?: defaultDeliveryFor(task.status)
        if (requestedDelivery == DeliveryMode.QUEUE && attachments.isNotEmpty()) return
        val delivery = if (snapshot.capabilities.explicitDelivery) requestedDelivery else null
        if (delivery == DeliveryMode.STEER && task.activeTurnId == null) {
            update { it.copy(error = "当前轮次状态尚未同步，请刷新后重试") }
            return
        }
        if (delivery == DeliveryMode.QUEUE && snapshot.queueHashByThread[threadId] == null) {
            update { it.copy(error = "排队状态尚未同步，请刷新后重试") }
            return
        }
        scope.launch {
            update { it.copy(sendingThreads = it.sendingThreads + threadId, error = null) }
            runCatching {
                requireApi().sendMessage(
                    threadId = threadId,
                    text = text,
                    delivery = delivery,
                    expectedTurnId = task.activeTurnId,
                    expectedQueueHash = snapshot.queueHashByThread[threadId],
                    attachmentIds = attachmentIds,
                )
                update { state ->
                    state.copy(
                        draftsByThread = state.draftsByThread + (threadId to ""),
                        attachmentsByThread = state.attachmentsByThread - threadId,
                    )
                }
                refreshNow()
            }.onFailure(::recordError)
            update { it.copy(sendingThreads = it.sendingThreads - threadId) }
        }
    }

    fun interrupt(threadId: String) {
        scope.launch {
            update { it.copy(stoppingThreads = it.stoppingThreads + threadId, error = null) }
            runCatching {
                requireApi().interrupt(threadId)
                refreshNow()
            }.onFailure(::recordError)
            update { it.copy(stoppingThreads = it.stoppingThreads - threadId) }
        }
    }

    fun cancelQueuedMessage(threadId: String, messageId: String) {
        val expectedHash = mutableState.value.queueHashByThread[threadId] ?: return
        scope.launch {
            runCatching {
                requireApi().cancelQueuedMessage(threadId, messageId, expectedHash)
                refreshNow()
            }.onFailure(::recordError)
        }
    }

    fun updateSettings(threadId: String, model: String, effort: String) {
        scope.launch {
            update { it.copy(settingsThreads = it.settingsThreads + threadId, error = null) }
            runCatching {
                requireApi().updateSettings(threadId, model, effort)
                refreshNow()
            }.onFailure(::recordError)
            update { it.copy(settingsThreads = it.settingsThreads - threadId) }
        }
    }

    fun loadDiff(threadId: String) {
        scope.launch {
            update { it.copy(loadingDiffThreads = it.loadingDiffThreads + threadId, error = null) }
            runCatching { requireApi().diff(threadId) }
                .onSuccess { diff -> update { it.copy(diffByThread = it.diffByThread + (threadId to diff.unifiedDiff())) } }
                .onFailure(::recordError)
            update { it.copy(loadingDiffThreads = it.loadingDiffThreads - threadId) }
        }
    }

    fun createTask(draft: CreateTaskDraft, onCreated: (() -> Unit)? = null) {
        if (!mutableState.value.capabilities.newTask) {
            update { it.copy(taskCreationError = "当前连接暂不支持远程新建任务") }
            return
        }
        scope.launch {
            update { it.copy(creatingTask = true, taskCreationError = null, error = null) }
            runCatching { requireApi().createTask(draft) }
                .onSuccess { response ->
                    refreshNow()
                    val threadId = response.task?.threadId ?: response.threadId
                    if (threadId != null) select(threadId)
                    val disposition = response.disposition()
                    if (disposition.closeDialog) {
                        if (onCreated != null) withContext(Dispatchers.Main.immediate) { onCreated() }
                    } else {
                        update { it.copy(taskCreationError = disposition.message) }
                    }
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        update { it.copy(taskCreationError = authenticatedBridgeErrorMessage(error)) }
                    }
                }
            update { it.copy(creatingTask = false) }
        }
    }

    fun clearTaskCreationError() {
        update { it.copy(taskCreationError = null) }
    }

    fun addAttachments(threadId: String, uris: List<Uri>) {
        val snapshot = mutableState.value
        if (!snapshot.capabilities.attachments.enabled || uris.isEmpty()) return
        val existing = snapshot.attachmentsByThread[threadId].orEmpty()
        val availableSlots = (MAX_COMPOSER_ATTACHMENTS - existing.size).coerceAtLeast(0)
        uris.take(availableSlots).forEach { uri ->
            val metadata = readAttachmentMetadata(uri)
            val localId = UUID.randomUUID().toString()
            val pending = ComposerAttachment(
                localId = localId,
                uri = uri.toString(),
                name = metadata.name,
                mimeType = metadata.mimeType,
                size = metadata.size,
                uploadState = AttachmentUploadState.UPLOADING,
            )
            update { state ->
                val current = state.attachmentsByThread[threadId].orEmpty()
                state.copy(attachmentsByThread = state.attachmentsByThread + (threadId to (current + pending)))
            }
            scope.launch { uploadAttachment(threadId, pending, uri) }
        }
    }

    fun removeAttachment(threadId: String, localId: String) {
        val attachment = mutableState.value.attachmentsByThread[threadId].orEmpty()
            .firstOrNull { it.localId == localId }
        update { state ->
            val remaining = state.attachmentsByThread[threadId].orEmpty().filterNot { it.localId == localId }
            state.copy(attachmentsByThread = if (remaining.isEmpty()) {
                state.attachmentsByThread - threadId
            } else state.attachmentsByThread + (threadId to remaining))
        }
        attachment?.attachmentId?.let { id -> scope.launch { runCatching { requireApi().deleteAttachment(id) } } }
    }

    private suspend fun uploadAttachment(threadId: String, pending: ComposerAttachment, uri: Uri) {
        runCatching {
            val bytes = applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("无法读取附件")
            val limit = mutableState.value.capabilities.maxAttachmentBytes
            if (bytes.size.toLong() > limit) {
                throw IllegalArgumentException("附件超过 ${formatFileSize(limit)} 限制")
            }
            requireApi().uploadAttachment(pending.name, pending.mimeType, bytes)
        }.onSuccess { uploaded ->
            replaceAttachment(threadId, pending.localId) {
                it.copy(
                    name = uploaded.name.ifBlank { it.name },
                    mimeType = uploaded.mimeType.ifBlank { it.mimeType },
                    size = uploaded.size.takeIf { size -> size > 0 } ?: it.size,
                    uploadState = AttachmentUploadState.READY,
                    attachmentId = uploaded.attachmentId,
                    error = null,
                )
            }
        }.onFailure { error ->
            replaceAttachment(threadId, pending.localId) {
                it.copy(uploadState = AttachmentUploadState.FAILED, error = error.message ?: "上传失败")
            }
        }
    }

    private fun replaceAttachment(
        threadId: String,
        localId: String,
        transform: (ComposerAttachment) -> ComposerAttachment,
    ) {
        update { state ->
            val attachments = state.attachmentsByThread[threadId].orEmpty().map {
                if (it.localId == localId) transform(it) else it
            }
            state.copy(attachmentsByThread = state.attachmentsByThread + (threadId to attachments))
        }
    }

    private fun readAttachmentMetadata(uri: Uri): LocalAttachmentMetadata {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "attachment" }
        var size = 0L
        applicationContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                    cursor.getString(index)?.takeIf(String::isNotBlank)?.let { name = it }
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { index ->
                    if (!cursor.isNull(index)) size = cursor.getLong(index).coerceAtLeast(0)
                }
            }
        }
        return LocalAttachmentMetadata(
            name = name,
            mimeType = applicationContext.contentResolver.getType(uri) ?: "application/octet-stream",
            size = size,
        )
    }

    fun requestPush(threadId: String) = action { requireApi().requestPush(threadId) }

    fun respondApproval(requestId: String, decision: String) = action {
        requireApi().respondApproval(requestId, decision)
    }

    fun respondUserInput(requestId: String, answers: Map<String, List<String>>) = action {
        requireApi().respondUserInput(requestId, answers)
    }

    fun disconnect() {
        val previousApi = api
        streamGeneration += 1
        selectionGeneration += 1
        cancelMediaLoads()
        cancelHistoryLoads()
        networkReconnectJob?.cancel()
        networkReconnectJob = null
        connectionStatusGrace.reset()
        stream?.cancel()
        stream = null
        reconnectScheduler.reset()
        refreshCoordinator?.dispose()
        refreshCoordinator = null
        restoreJob?.cancel()
        restoreJob = null
        followedThreadId = null
        api = null
        activeConnection = null
        store.clear()
        pendingReviewStore.clear()
        snapshotCache.clearAll()
        applicationContext.stopService(Intent(applicationContext, RemoteService::class.java))
        mutableState.value = RemoteState()
        scope.launch {
            runCatching { previousApi?.revokeSelf() }
            previousApi?.close()
        }
    }

    private fun configure(saved: StoredConnection) {
        authorizationExpired = false
        activeConnection = saved
        refreshCoordinator?.dispose()
        refreshCoordinator = ConflatedRefreshCoordinator(scope) {
            refreshWithRecovery()
        }
        api = BridgeApi(
            saved.serverUrl,
            saved.token,
            ConnectionHttpClientFactory.create(applicationContext, saved.routeMode),
        )
        mediaCacheKey = "${saved.id}:${saved.deviceId}"
        update {
            it.copy(
                configured = true,
                serverUrl = saved.serverUrl,
                activeConnectionId = saved.id,
                connectionRouteMode = saved.routeMode,
                serverAddresses = store.serverAddresses(),
                taskListLoading = it.tasks.isEmpty(),
                error = null,
            )
        }
    }

    private suspend fun activateConnection(saved: StoredConnection) {
        val previousApi = api
        streamGeneration += 1
        selectionGeneration += 1
        cancelMediaLoads()
        cancelHistoryLoads()
        networkReconnectJob?.cancel()
        networkReconnectJob = null
        connectionStatusGrace.reset()
        stream?.cancel()
        stream = null
        reconnectScheduler.reset()
        refreshCoordinator?.dispose()
        refreshCoordinator = null
        followedThreadId = null
        api = null
        previousApi?.close()
        pendingReviewStore.clear()
        val cachedTasks = snapshotCache.loadTasks(cacheKey(saved))
        mutableState.value = RemoteState(
            configured = true,
            loading = true,
            taskListLoading = cachedTasks.isEmpty(),
            serverUrl = saved.serverUrl,
            activeConnectionId = saved.id,
            connectionRouteMode = saved.routeMode,
            serverAddresses = store.serverAddresses(),
            tasks = cachedTasks,
        )
        configure(saved)
        openStream()
        refreshWithRecovery()
    }

    private fun openStream() {
        val bridge = api ?: return
        if (authorizationExpired) return
        reconnectScheduler.cancel()
        val generation = ++streamGeneration
        stream?.cancel()
        stream = bridge.stream(
            onEvent = {
                if (generation == streamGeneration) scheduleRefresh(150)
            },
            onConnected = { connected ->
                if (generation != streamGeneration) return@stream
                if (connected) {
                    connectionStatusGrace.connected()
                    reconnectScheduler.reset()
                    scheduleRefresh(0)
                } else {
                    connectionStatusGrace.disconnected()
                    followedThreadId = null
                    if (!authorizationExpired) reconnectScheduler.schedule()
                }
            },
            onConnectionFailure = { error ->
                if (generation == streamGeneration && isAuthorizationFailure(error)) {
                    markAuthorizationExpired(error)
                }
            },
        )
    }

    private suspend fun refreshNow() {
        val bridge = requireApi()
        if (mutableState.value.tasks.isEmpty()) {
            update { it.copy(taskListLoading = true) }
        }
        val health = bridge.health()
        if (api !== bridge) return
        update {
            it.copy(
                writeSupported = health.compatibility.supported,
                compatibilityVerified = health.compatibility.verified,
                ipcConnected = health.ipc == "connected",
                desktopVersion = health.compatibility.installed,
                error = null,
            )
        }
        val requestedThreadId = mutableState.value.selectedThreadId
        if (
            health.ipc == "connected" &&
            requestedThreadId != null &&
            followedThreadId != requestedThreadId
        ) {
            runCatching { bridge.follow(requestedThreadId) }
                .onSuccess { followedThreadId = requestedThreadId }
        }
        val payload = coroutineScope {
            val capabilities = async {
                runCatching { bridge.capabilities() }.getOrDefault(RemoteCapabilitiesDto())
            }
            val models = async { runCatching { bridge.models() }.getOrDefault(emptyList()) }
            val tasks = async { bridge.tasks() }
            val approvals = async { bridge.approvals() }
            RefreshPayload(
                capabilities = capabilities.await(),
                models = models.await(),
                tasks = tasks.await(),
                approvals = approvals.await(),
            )
        }
        if (api !== bridge) return
        val completedReviewThreadIds = pendingReviewStore.observe(payload.tasks)
        val requestedSelection = mutableState.value.selectedThreadId
        val latestDetailResult = requestedSelection?.let { selected ->
            runCatching { bridge.taskDetail(selected) }
        }
        val latestDetail = latestDetailResult?.getOrNull()
        val latestDetailFailure = latestDetailResult?.exceptionOrNull()
        val detailFailurePresentation = latestDetailFailure?.let {
            refreshFailurePresentation(it, mutableState.value)
        }
        val queue = if (requestedSelection != null && payload.capabilities.queue) {
            runCatching { bridge.queue(requestedSelection) }.getOrNull()
        } else null
        if (api !== bridge) return
        var detailToCache: TaskDetailDto? = null
        update { current ->
            val resolved = resolveSelectedTaskRefresh(
                selectedThreadId = current.selectedThreadId,
                currentDetail = current.taskDetail,
                tasks = payload.tasks,
                requestedThreadId = requestedSelection,
                fetchedDetail = latestDetail,
            )
            val visibleMediaIds = resolved.detail?.items?.mapNotNull { it.media?.mediaId }?.toSet().orEmpty()
            detailToCache = resolved.detail
            current.copy(
                configured = true,
                tasks = payload.tasks,
                taskDetail = resolved.detail,
                approvals = payload.approvals,
                completedReviewThreadIds = completedReviewThreadIds,
                selectedThreadId = resolved.threadId,
                capabilities = payload.capabilities,
                models = payload.models,
                taskListLoading = false,
                queueByThread = if (requestedSelection != null && resolved.threadId == requestedSelection && queue != null) {
                    current.queueByThread + (requestedSelection to queue.values())
                } else current.queueByThread,
                queueHashByThread = if (requestedSelection != null && resolved.threadId == requestedSelection && queue != null) {
                    current.queueHashByThread + (requestedSelection to queue.hash)
                } else current.queueHashByThread,
                taskMediaById = current.taskMediaById.filterKeys { it in visibleMediaIds },
                loadingTaskMediaIds = current.loadingTaskMediaIds.filterTo(mutableSetOf()) {
                    it in visibleMediaIds
                },
                failedTaskMediaIds = current.failedTaskMediaIds.filterTo(mutableSetOf()) {
                    it in visibleMediaIds
                },
                syncingThreadIds = when {
                    requestedSelection == null -> current.syncingThreadIds
                    resolved.threadId != requestedSelection -> current.syncingThreadIds - requestedSelection
                    latestDetail != null -> current.syncingThreadIds - requestedSelection
                    detailFailurePresentation?.shouldRetry == true ->
                        current.syncingThreadIds + requestedSelection
                    else -> current.syncingThreadIds - requestedSelection
                },
                error = detailFailurePresentation?.message?.takeIf {
                    detailFailurePresentation.isError
                },
            )
        }
        activeCacheKey()?.let { key ->
            runCatching { snapshotCache.saveTasks(key, payload.tasks) }
            detailToCache?.let { detail ->
                runCatching { snapshotCache.saveDetail(key, detail) }
            }
        }
        if (detailFailurePresentation?.shouldRetry == true) scheduleRefresh(1_500)
    }

    private suspend fun refreshWithRecovery() {
        val expectedApi = api
        try {
            refreshNow()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (api !== expectedApi) return
            val presentation = refreshFailurePresentation(error, mutableState.value)
            if (isAuthorizationFailure(error)) markAuthorizationExpired(error)
            update {
                it.copy(
                    connected = if (isAuthorizationFailure(error)) false else it.connected,
                    taskListLoading = presentation.shouldRetry && it.tasks.isEmpty(),
                    error = presentation.message.takeIf { presentation.isError },
                )
            }
            if (presentation.shouldRetry) scheduleRefresh(3_000)
        }
    }

    fun loadTaskMedia(threadId: String, mediaId: String) {
        val snapshot = mutableState.value
        if (snapshot.selectedThreadId != threadId || snapshot.taskMediaById[mediaId]?.isFile == true) return
        val bridge = api ?: return
        val cacheKey = "$mediaCacheKey:$threadId:$mediaId"
        synchronized(mediaJobs) {
            if (mediaJobs[mediaId]?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                update {
                    it.copy(
                        loadingTaskMediaIds = it.loadingTaskMediaIds + mediaId,
                        failedTaskMediaIds = it.failedTaskMediaIds - mediaId,
                    )
                }
                try {
                    val file = mediaCache.load(cacheKey) { bridge.taskMedia(threadId, mediaId, it) }
                    if (api === bridge) {
                        update { state ->
                            val stillVisible = state.selectedThreadId == threadId &&
                                state.taskDetail?.items?.any {
                                    it.media?.mediaId == mediaId
                                } == true
                            state.copy(
                                taskMediaById = if (stillVisible) {
                                    state.taskMediaById + (mediaId to file)
                                } else state.taskMediaById,
                                failedTaskMediaIds = state.failedTaskMediaIds - mediaId,
                            )
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (api === bridge && mutableState.value.selectedThreadId == threadId) {
                        update { it.copy(failedTaskMediaIds = it.failedTaskMediaIds + mediaId) }
                    }
                } finally {
                    synchronized(mediaJobs) {
                        if (mediaJobs[mediaId] == coroutineContext[Job]) {
                            mediaJobs.remove(mediaId)
                            update { it.copy(loadingTaskMediaIds = it.loadingTaskMediaIds - mediaId) }
                        }
                    }
                }
            }
            mediaJobs[mediaId] = job
            job.start()
        }
    }

    private fun cancelMediaLoads() {
        synchronized(mediaJobs) {
            mediaJobs.values.forEach(Job::cancel)
            mediaJobs.clear()
        }
    }

    private fun cancelHistoryLoads() {
        synchronized(historyJobs) {
            historyJobs.values.forEach(Job::cancel)
            historyJobs.clear()
        }
    }

    @Synchronized
    private fun scheduleNetworkReconnect() {
        val saved = activeConnection ?: return
        if (authorizationExpired) return
        networkReconnectJob?.cancel()
        networkReconnectJob = scope.launch {
            delay(NETWORK_RECONNECT_DEBOUNCE_MS)
            if (activeConnection?.id != saved.id || authorizationExpired) return@launch
            runCatching { rebuildTransport(saved) }
                .onFailure { error ->
                    if (activeConnection?.id == saved.id && !authorizationExpired) {
                        recordError(error)
                    }
                }
        }
    }

    private suspend fun rebuildTransport(saved: StoredConnection) {
        val previousApi = api
        streamGeneration += 1
        cancelMediaLoads()
        cancelHistoryLoads()
        stream?.cancel()
        stream = null
        reconnectScheduler.reset()
        refreshCoordinator?.dispose()
        refreshCoordinator = null
        followedThreadId = null
        api = null
        previousApi?.close()
        update {
            it.copy(
                taskListLoading = it.tasks.isEmpty(),
                loadingOlderHistoryThreads = emptySet(),
                loadingAllHistoryThreads = emptySet(),
                historyLoadProgressByThread = emptyMap(),
                error = null,
            )
        }
        configure(saved)
        openStream()
        refreshWithRecovery()
    }

    private fun cacheKey(saved: StoredConnection): String = "${saved.id}:${saved.deviceId}"

    private fun activeCacheKey(): String? = activeConnection?.let(::cacheKey)

    private fun saveCachedDetail(detail: TaskDetailDto) {
        activeCacheKey()?.let { key ->
            runCatching { snapshotCache.saveDetail(key, detail) }
        }
    }

    private fun action(onSuccess: (() -> Unit)? = null, block: suspend () -> Unit) {
        scope.launch {
            update { it.copy(loading = true, error = null) }
            runCatching {
                block()
                refreshNow()
            }.onSuccess {
                if (onSuccess != null) withContext(Dispatchers.Main.immediate) { onSuccess() }
            }.onFailure(::recordError)
            update { it.copy(loading = false) }
        }
    }

    private fun requireApi(): BridgeApi = requireNotNull(api) { "Bridge is not configured" }
    private fun recordError(error: Throwable) {
        if (error is CancellationException) return
        if (isAuthorizationFailure(error)) markAuthorizationExpired(error)
        update { it.copy(error = authenticatedBridgeErrorMessage(error)) }
    }
    private fun markAuthorizationExpired(error: Throwable) {
        authorizationExpired = true
        reconnectScheduler.cancel()
        followedThreadId = null
        streamGeneration += 1
        stream?.cancel()
        stream = null
        connectionStatusGrace.disconnected(immediate = true)
        update {
            it.copy(
                connected = false,
                taskListLoading = false,
                error = authenticatedBridgeErrorMessage(error),
            )
        }
    }
    private fun update(block: (RemoteState) -> RemoteState) { mutableState.update(block) }

    private fun scheduleRefresh(delayMs: Long) {
        refreshCoordinator?.request(delayMs)
    }

    private fun startListenerService() {
        ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, RemoteService::class.java),
        )
    }

    companion object {
        private const val MAX_COMPOSER_ATTACHMENTS = 8
        private const val NETWORK_RECONNECT_DEBOUNCE_MS = 650L
        @Volatile private var instance: RemoteRepository? = null
        fun get(context: Context): RemoteRepository = instance ?: synchronized(this) {
            instance ?: RemoteRepository(context.applicationContext).also { instance = it }
        }
    }
}

private data class LocalAttachmentMetadata(val name: String, val mimeType: String, val size: Long)

private data class RefreshPayload(
    val capabilities: RemoteCapabilitiesDto,
    val models: List<ModelOptionDto>,
    val tasks: List<TaskDto>,
    val approvals: List<ApprovalDto>,
)

private fun formatFileSize(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
    size >= 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "$size B"
}
