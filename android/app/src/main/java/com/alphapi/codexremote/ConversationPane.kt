package com.alphapi.codexremote

import android.graphics.Bitmap
import java.io.File
import android.net.Uri
import android.content.Intent
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Composable
internal fun TaskConversationPane(
    task: TaskDto,
    detail: TaskDetailDto?,
    canWrite: Boolean,
    activating: Boolean = false,
    syncing: Boolean = false,
    loadingOlderHistory: Boolean = false,
    loadingAllHistory: Boolean = false,
    historyPagesLoaded: Int = 0,
    historyLoadFailed: Boolean = false,
    draft: String,
    deliveryMode: DeliveryMode,
    queued: List<QueuedFollowUpDto>,
    queueReady: Boolean,
    attachments: List<ComposerAttachment>,
    taskMediaById: Map<String, File> = emptyMap(),
    loadingTaskMediaIds: Set<String> = emptySet(),
    failedTaskMediaIds: Set<String> = emptySet(),
    workspaceFiles: List<WorkspaceFileDto> = emptyList(),
    workspaceFilesLoading: Boolean = false,
    models: List<ModelOptionDto>,
    capabilities: RemoteCapabilitiesDto,
    sending: Boolean,
    stopping: Boolean,
    onDraftChange: (String) -> Unit,
    onDeliveryChange: (DeliveryMode) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onCancelQueued: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onAttachmentsSelected: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenResource: (TimelineResourceDto) -> Unit = {},
    onLoadWorkspaceFiles: (String) -> Unit = {},
    onWorkspaceFileSelected: (WorkspaceFileDto) -> Unit = {},
    onLoadTaskMedia: (String) -> Unit = {},
    onLoadOlderHistory: () -> Unit = {},
    onLoadAllHistory: () -> Unit = {},
) {
    var showWorkspaceFiles by rememberSaveable { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5),
        onAttachmentsSelected,
    )
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
        onAttachmentsSelected,
    )
    val active = task.status == "active" || task.status == "inProgress"
    val effectiveDelivery = if (active) {
        deliveryMode.takeIf { it == DeliveryMode.STEER || it == DeliveryMode.QUEUE } ?: DeliveryMode.STEER
    } else DeliveryMode.START

    Column(Modifier.fillMaxSize()) {
        taskConnectionPresentation(task.ownerAvailable, activating, syncing)?.let {
            ConnectionBanner(it)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                detail == null -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Text("正在同步最新记录", style = MaterialTheme.typography.bodySmall)
                }
                else -> ConversationTimeline(
                    detail,
                    taskMediaById,
                    loadingTaskMediaIds,
                    failedTaskMediaIds,
                    onOpenResource,
                    onLoadTaskMedia,
                    loadingOlderHistory,
                    loadingAllHistory,
                    historyPagesLoaded,
                    historyLoadFailed,
                    onLoadOlderHistory,
                    onLoadAllHistory,
                )
            }
        }
        if (queued.isNotEmpty()) {
            QueuedFollowUps(queued, canWrite, onCancelQueued)
        }
        MessageComposer(
            value = draft,
            onValueChange = onDraftChange,
            deliveryMode = effectiveDelivery,
            active = active,
            queueSupported = capabilities.queue && capabilities.explicitDelivery,
            attachmentsSupported = capabilities.attachments.enabled,
            attachments = attachments,
            canSend = canWrite && !sending && (draft.isNotBlank() || attachments.isNotEmpty()) &&
                attachments.none { it.uploadState != AttachmentUploadState.READY } &&
                (effectiveDelivery != DeliveryMode.STEER || task.activeTurnId != null || !capabilities.explicitDelivery) &&
                (effectiveDelivery != DeliveryMode.QUEUE || (attachments.isEmpty() && queueReady)),
            sending = sending,
            stopping = stopping,
            canStop = canWrite && active,
            modelLabel = models.firstOrNull { it.id == task.settings?.model }?.displayName ?: task.settings?.model,
            reasoningEffort = task.settings?.effort,
            settingsEnabled = capabilities.threadSettings && models.isNotEmpty(),
            onDeliveryChange = onDeliveryChange,
            onSend = onSend,
            onStop = onStop,
            onOpenSettings = onOpenSettings,
            onPickImages = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
            onPickRemoteFiles = {
                showWorkspaceFiles = true
                onLoadWorkspaceFiles("")
            },
            onRemoveAttachment = onRemoveAttachment,
        )
    }
    if (showWorkspaceFiles) {
        WorkspaceFilePickerDialog(
            files = workspaceFiles,
            loading = workspaceFilesLoading,
            onSearch = onLoadWorkspaceFiles,
            onSelect = {
                onWorkspaceFileSelected(it)
                showWorkspaceFiles = false
            },
            onDismiss = { showWorkspaceFiles = false },
        )
    }
}

@Composable
private fun QueuedFollowUps(
    queued: List<QueuedFollowUpDto>,
    canWrite: Boolean,
    onCancel: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 6.dp)) {
            Text("已排队 ${queued.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            queued.forEach { message ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.text,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    IconButton(onClick = { onCancel(message.id) }, enabled = canWrite, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, "取消排队消息", modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionBanner(presentation: ConnectionStatusPresentation) {
    val containerColor = if (presentation.isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (presentation.isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = containerColor, modifier = Modifier.fillMaxWidth()) {
        Text(
            presentation.text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = contentColor,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ConversationTimeline(
    detail: TaskDetailDto,
    taskMediaById: Map<String, File>,
    loadingTaskMediaIds: Set<String>,
    failedTaskMediaIds: Set<String>,
    onOpenResource: (TimelineResourceDto) -> Unit,
    onLoadTaskMedia: (String) -> Unit,
    loadingOlderHistory: Boolean,
    loadingAllHistory: Boolean,
    historyPagesLoaded: Int,
    historyLoadFailed: Boolean,
    onLoadOlderHistory: () -> Unit,
    onLoadAllHistory: () -> Unit,
) {
    val context = LocalContext.current
    val blocks = remember(detail.items) { presentConversation(detail.items) }
    val resourcesById = remember(detail.items) {
        detail.items.flatMap { it.resources }.associateBy { it.resourceId }
    }
    val markwon = remember(context, resourcesById, onOpenResource) {
        Markwon.builder(context)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        if (link.startsWith("codexremote://resource/")) {
                            val resourceId = link.substringAfterLast('/')
                            resourcesById[resourceId]?.let(onOpenResource)
                            return@linkResolver
                        }
                        if (!isAllowedExternalLink(link)) return@linkResolver
                        runCatching {
                            val intent = Intent.createChooser(
                                Intent(Intent.ACTION_VIEW, Uri.parse(link)),
                                "打开链接",
                            )
                            view.context.startActivity(intent)
                        }
                    }
                }
            })
            .build()
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var positionedInitially by remember(detail.threadId) { mutableStateOf(false) }
    var autoRequestedCursor by remember(detail.threadId) { mutableStateOf<String?>(null) }
    var automaticallyLoadedPages by remember(detail.threadId) { mutableIntStateOf(0) }
    val showJumpToLatest by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            total > 2 && lastVisible < total - 2
        }
    }
    LaunchedEffect(detail.threadId, detail.revision, detail.items.size) {
        val total = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearLatest = lastVisible >= total - 4
        if (!positionedInitially || nearLatest) {
            listState.animateScrollToItem(total - 1)
        }
        positionedInitially = true
    }

    LaunchedEffect(
        detail.threadId,
        positionedInitially,
        detail.hasMoreHistory,
        detail.historyCursor,
        loadingOlderHistory,
    ) {
        if (
            !positionedInitially ||
            !detail.hasMoreHistory ||
            detail.historyCursor == null ||
            loadingOlderHistory
        ) {
            return@LaunchedEffect
        }
        var previous = listState.historyViewport()
        snapshotFlow { listState.historyViewport() }.collect { current ->
            val canAutoLoad = canAutomaticallyLoadHistory(
                pagesLoaded = automaticallyLoadedPages,
                lastRequestedCursor = autoRequestedCursor,
                currentCursor = detail.historyCursor,
            )
            if (shouldRequestOlderHistory(previous, current) && canAutoLoad && !historyLoadFailed) {
                autoRequestedCursor = detail.historyCursor
                automaticallyLoadedPages += 1
                onLoadOlderHistory()
            }
            previous = current
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("conversationTimeline"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        ) {
            if (detail.hasMoreHistory && historyLoadFailed && !loadingOlderHistory) {
                item("older-history-retry") {
                    Row(
                        Modifier.fillMaxWidth().height(44.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onLoadOlderHistory) {
                            Icon(Icons.Default.History, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重试加载更早记录")
                        }
                    }
                }
            } else if (
                detail.hasMoreHistory &&
                automaticallyLoadedPages >= AUTOMATIC_HISTORY_PAGE_LIMIT &&
                !loadingOlderHistory
            ) {
                item("load-all-history") {
                    Row(
                        Modifier.fillMaxWidth().height(44.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onLoadAllHistory,
                            modifier = Modifier.testTag("loadAllHistory"),
                        ) {
                            Icon(Icons.Default.History, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("加载全部历史记录")
                        }
                    }
                }
            }
            if (detail.hasMoreHistory && loadingOlderHistory) {
                item("older-history") {
                    Row(
                        Modifier.fillMaxWidth().height(36.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (loadingAllHistory) {
                                "正在加载全部历史记录（已加载 $historyPagesLoaded 页）"
                            } else {
                                "正在加载更早记录"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            if (detail.items.isEmpty() && detail.status != "active") {
                item("empty") {
                    Text(
                        "这个任务还没有可显示的对话",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        color = Color(0xFF777772),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            itemsIndexed(
                blocks,
                key = { _, block -> when (block) {
                    is ConversationBlock.Item -> "${block.item.turnId}:${block.item.id}"
                    is ConversationBlock.Process -> "${block.turnId}:process:${block.items.firstOrNull()?.id}"
                    is ConversationBlock.QuestionReply -> "${block.turnId}:question:${block.sourceItemId}"
                } },
            ) { index, block ->
                val turnId = when (block) {
                    is ConversationBlock.Item -> block.item.turnId
                    is ConversationBlock.Process -> block.turnId
                    is ConversationBlock.QuestionReply -> block.turnId
                }
                val previousTurnId = blocks.getOrNull(index - 1)?.let {
                    when (it) {
                        is ConversationBlock.Item -> it.item.turnId
                        is ConversationBlock.Process -> it.turnId
                        is ConversationBlock.QuestionReply -> it.turnId
                    }
                }
                val startsTurn = index == 0 || previousTurnId != turnId
                Spacer(Modifier.height(if (startsTurn) 18.dp else 8.dp))
                when (block) {
                    is ConversationBlock.Item -> ConversationEntry(
                        block.item,
                        markwon,
                        taskMediaById[block.item.media?.mediaId],
                        block.item.media?.mediaId in loadingTaskMediaIds,
                        block.item.media?.mediaId in failedTaskMediaIds,
                        onLoadTaskMedia,
                    )
                    is ConversationBlock.Process -> ProcessDisclosure(
                        block,
                        markwon,
                        taskMediaById,
                        loadingTaskMediaIds,
                        failedTaskMediaIds,
                        onLoadTaskMedia,
                    )
                    is ConversationBlock.QuestionReply -> QuestionReplyCard(block)
                }
            }
            if (detail.status == "active") {
                item("working") {
                    WorkingIndicator(Modifier.padding(top = 14.dp, bottom = 10.dp))
                }
            }
        }
        if (showJumpToLatest) {
            SmallFloatingActionButton(
                onClick = {
                    val last = listState.layoutInfo.totalItemsCount - 1
                    if (last >= 0) {
                        coroutineScope.launch { listState.animateScrollToItem(last) }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, "回到最新消息")
            }
        }
    }
}

@Composable
private fun QuestionReplyCard(block: ConversationBlock.QuestionReply) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Surface(
            color = Color(0xFFF0F0ED),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "已回答的问题",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                block.entries.forEach { entry ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.question, style = MaterialTheme.typography.bodyMedium)
                        if (entry.answer.isNotBlank()) {
                            Text(
                                "已选择：${entry.answer}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListState.historyViewport() = HistoryViewport(
    firstVisibleItemIndex = firstVisibleItemIndex,
    firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
    totalItemsCount = layoutInfo.totalItemsCount,
    canScrollBackward = canScrollBackward,
    canScrollForward = canScrollForward,
)

@Composable
private fun ProcessDisclosure(
    block: ConversationBlock.Process,
    markwon: Markwon,
    taskMediaById: Map<String, File>,
    loadingTaskMediaIds: Set<String>,
    failedTaskMediaIds: Set<String>,
    onLoadTaskMedia: (String) -> Unit,
) {
    var expanded by rememberSaveable(block.turnId, block.items.firstOrNull()?.id) {
        mutableStateOf(false)
    }
    Column(Modifier.fillMaxWidth().animateContentSize().testTag("processDisclosure:${block.turnId}")) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                block.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF676762),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                if (expanded) "收起处理过程" else "展开处理过程",
                modifier = Modifier.size(20.dp),
                tint = Color(0xFF676762),
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.items.forEach { item ->
                    ConversationEntry(
                        item,
                        markwon,
                        taskMediaById[item.media?.mediaId],
                        item.media?.mediaId in loadingTaskMediaIds,
                        item.media?.mediaId in failedTaskMediaIds,
                        onLoadTaskMedia,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationEntry(
    item: TimelineItemDto,
    markwon: Markwon,
    mediaFile: File?,
    mediaLoading: Boolean,
    mediaFailed: Boolean,
    onLoadTaskMedia: (String) -> Unit,
) {
    when (item.kind) {
        "user" -> UserMessage(item.text, markwon)
        "userImage" -> TimelineImage(item.media, mediaFile, mediaLoading, mediaFailed, onLoadTaskMedia, fromUser = true)
        "assistant" -> AssistantMessage(item.text, markwon)
        "plan" -> PlanMessage(item.text, markwon)
        "image" -> TimelineImage(item.media, mediaFile, mediaLoading, mediaFailed, onLoadTaskMedia)
        "command", "file" -> ActivityDisclosure(item)
        else -> ProgressMessage(item.text, markwon)
    }
}

@Composable
private fun TimelineImage(
    media: TimelineMediaDto?,
    file: File?,
    loading: Boolean,
    failed: Boolean,
    onLoadTaskMedia: (String) -> Unit,
    fromUser: Boolean = false,
) {
    if (media == null) return
    LaunchedEffect(media.mediaId, file) {
        if (file?.isFile != true) onLoadTaskMedia(media.mediaId)
    }
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file) {
        bitmap = withContext(Dispatchers.IO) { file?.let(::decodeTaskImage) }
    }
    val displayedBitmap = bitmap
    var expanded by rememberSaveable(media.mediaId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Column(
            Modifier
                .widthIn(max = if (fromUser) 344.dp else 720.dp)
                .align(if (fromUser) Alignment.End else Alignment.Start),
        ) {
            Text(
                if (fromUser) "你发送的图片" else "Codex 图片",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Surface(
                color = Color(0xFFF0F0ED),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    displayedBitmap != null -> Image(
                        bitmap = displayedBitmap.asImageBitmap(),
                        contentDescription = media.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(displayedBitmap.width.toFloat() / displayedBitmap.height.coerceAtLeast(1))
                            .heightIn(max = 420.dp)
                            .clickable { expanded = true }
                            .testTag("timelineImage:${media.mediaId}"),
                    )
                    failed -> Box(
                        Modifier.fillMaxWidth().height(140.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(onClick = { onLoadTaskMedia(media.mediaId) }) {
                            Text("重试加载图片", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    loading -> Box(
                        Modifier.fillMaxWidth().height(140.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    else -> Box(
                        Modifier.fillMaxWidth().height(140.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("正在准备图片", color = Color(0xFF777772)) }
                }
            }
            Text(
                media.name,
                modifier = Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF666661),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (expanded && displayedBitmap != null) {
        Dialog(
            onDismissRequest = { expanded = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xEE101010))
                    .clickable { expanded = false },
            ) {
                Image(
                    bitmap = displayedBitmap.asImageBitmap(),
                    contentDescription = media.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                )
                IconButton(
                    onClick = { expanded = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    Icon(Icons.Default.Close, "关闭图片", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun UserMessage(text: String, markwon: Markwon) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = Color(0xFFE9E9E5),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.widthIn(max = 344.dp),
        ) {
            MarkdownBody(
                markdown = text,
                markwon = markwon,
                textSizeSp = 17f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessage(text: String, markwon: Markwon) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            "Codex",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        MarkdownBody(text, markwon)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(text)) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Default.ContentCopy, "复制回复", modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun PlanMessage(text: String, markwon: Markwon) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Checklist, null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(7.dp))
            Text("计划", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(5.dp))
        MarkdownBody(text, markwon, textSizeSp = 16f)
    }
}

@Composable
private fun ProgressMessage(text: String, markwon: Markwon) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Box(
            Modifier.padding(top = 8.dp).size(7.dp).background(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.shapes.small,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            MarkdownBody(text, markwon, textSizeSp = 15f, textColor = Color(0xFF454542))
        }
    }
}

@Composable
private fun MarkdownBody(
    markdown: String,
    markwon: Markwon,
    textSizeSp: Float = 17f,
    textColor: Color = Color(0xFF232321),
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val segments = remember(markdown) { splitMarkdownSegments(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is MarkdownSegment.Prose -> MarkdownProse(
                    markdown = segment.markdown,
                    markwon = markwon,
                    textSizeSp = textSizeSp,
                    textColor = textColor,
                    key = index,
                )
                is MarkdownSegment.Code -> CodeBlock(segment.code, segment.language)
                is MarkdownSegment.Table -> MarkdownTable(segment, markwon, textColor)
            }
        }
    }
}

@Composable
private fun MarkdownTable(
    table: MarkdownSegment.Table,
    markwon: Markwon,
    textColor: Color,
) {
    val columnWidths = remember(table) {
        table.header.indices.map { column ->
            val widest = (listOf(table.header[column]) + table.rows.map { it[column] })
                .maxOf(::markdownCellWidth)
            (widest * 8 + 28).coerceIn(100, 220).dp
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("markdownTable"),
    ) {
        (listOf(table.header) + table.rows).forEachIndexed { rowIndex, row ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                row.forEachIndexed { columnIndex, cell ->
                    Box(
                        Modifier
                            .width(columnWidths[columnIndex])
                            .fillMaxHeight()
                            .background(if (rowIndex == 0) Color(0xFFE9ECE9) else Color.Transparent)
                            .border(0.5.dp, Color(0xFFB8BDBA))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        MarkdownProse(
                            markdown = if (rowIndex == 0) "**$cell**" else cell,
                            markwon = markwon,
                            textSizeSp = 14f,
                            textColor = textColor,
                            key = rowIndex * table.header.size + columnIndex,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private fun markdownCellWidth(value: String): Int = value.fold(0) { width, character ->
    width + if (character.code >= 0x2E80) 2 else 1
}.coerceAtLeast(4)

@Composable
private fun MarkdownProse(
    markdown: String,
    markwon: Markwon,
    textSizeSp: Float,
    textColor: Color,
    key: Int,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val accessibleText = remember(markdown) { markdownAccessibilityText(markdown) }
    AndroidView(
        factory = { viewContext ->
            TextView(viewContext).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
                setLineSpacing(0f, 1.2f)
                includeFontPadding = false
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            view.setTextColor(textColor.toArgb())
            view.setLinkTextColor(linkColor)
            view.tag = key
            markwon.setMarkdown(view, markdown)
            view.contentDescription = view.text
        },
        modifier = modifier.semantics {
            contentDescription = accessibleText
        },
    )
}

private fun markdownAccessibilityText(markdown: String): String = markdown
    .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
    .replace(Regex("\\*{2,3}([^*]+)\\*{2,3}"), "$1")
    .replace(Regex("(?m)^\\s*[-*+]\\s+"), "• ")
    .replace(Regex("`([^`]+)`"), "$1")

@Composable
private fun CodeBlock(code: String, language: String? = null) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth().background(Color(0xFFF0F0ED), MaterialTheme.shapes.small),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language ?: "code",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF666661),
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(code)) },
                modifier = Modifier.size(34.dp),
            ) { Icon(Icons.Default.ContentCopy, "复制代码", modifier = Modifier.size(16.dp)) }
        }
        SelectionContainer {
            Text(
                code,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 11.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Color(0xFF272725),
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ActivityDisclosure(item: TimelineItemDto) {
    val presentation = remember(item.text, item.kind) { presentActivity(item) }
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val icon = if (item.kind == "command") Icons.Default.Terminal else Icons.Default.Description
    Surface(
        color = Color(0xFFF0F0ED),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, modifier = Modifier.size(18.dp), tint = Color(0xFF555550))
                Spacer(Modifier.width(9.dp))
                Text(
                    presentation.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.status?.takeIf { it == "active" || it == "inProgress" || it == "failed" }?.let {
                    Text(statusLabel(it), style = MaterialTheme.typography.labelSmall, color = Color(0xFF6B6B66))
                }
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(36.dp)) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起" else "展开")
                }
            }
            if (expanded && presentation.detail.isNotBlank()) {
                HorizontalDivider(color = Color(0xFFDDDDD8))
                CodeBlock(presentation.detail, if (item.kind == "command") "terminal" else "files")
            }
        }
    }
}

@Composable
private fun WorkingIndicator(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(9.dp))
        Text("Codex 正在处理", style = MaterialTheme.typography.bodySmall, color = Color(0xFF676762))
    }
}

@Composable
private fun MessageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    deliveryMode: DeliveryMode,
    active: Boolean,
    queueSupported: Boolean,
    attachmentsSupported: Boolean,
    attachments: List<ComposerAttachment>,
    canSend: Boolean,
    sending: Boolean,
    stopping: Boolean,
    canStop: Boolean,
    modelLabel: String?,
    reasoningEffort: String?,
    settingsEnabled: Boolean,
    onDeliveryChange: (DeliveryMode) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
    onPickRemoteFiles: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().imePadding(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("messageComposerContent")
                .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().testTag("composerSetupControls"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (attachmentsSupported) {
                    IconButton(onClick = onPickImages, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.AddPhotoAlternate, "选择图片", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onPickFiles, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.AttachFile, "选择文件", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onPickRemoteFiles, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Computer, "选择电脑文件", modifier = Modifier.size(20.dp))
                    }
                }
                if (settingsEnabled) {
                    AssistChip(
                        onClick = onOpenSettings,
                        label = {
                            Text(
                                listOfNotNull(modelLabel, reasoningEffort?.let(::effortLabel)).joinToString(" ").ifBlank { "模型" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Tune, null, modifier = Modifier.size(17.dp)) },
                        modifier = Modifier.padding(horizontal = 4.dp).weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
            if (attachments.isNotEmpty()) {
                AttachmentPreviewRow(attachments, onRemoveAttachment)
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("给 Codex 发消息") },
                minLines = 1,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().testTag("composerInput"),
                shape = MaterialTheme.shapes.small,
            )
            Row(
                Modifier.fillMaxWidth().testTag("composerDeliveryControls"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (active && queueSupported) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = deliveryMode == DeliveryMode.STEER,
                            onClick = { onDeliveryChange(DeliveryMode.STEER) },
                            label = { Text("调整方向") },
                        )
                        FilterChip(
                            selected = deliveryMode == DeliveryMode.QUEUE,
                            onClick = { onDeliveryChange(DeliveryMode.QUEUE) },
                            label = { Text("加入队列") },
                        )
                    }
                } else {
                    Text(
                        if (active) "发送到当前轮次" else "开始新一轮",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.weight(1f))
                }
                if (canStop) {
                    IconButton(onClick = onStop, enabled = !stopping, modifier = Modifier.size(44.dp)) {
                        if (stopping) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Stop, "停止当前任务")
                    }
                }
                IconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(44.dp)) {
                    if (sending && value.isNotBlank()) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, "发送")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFilePickerDialog(
    files: List<WorkspaceFileDto>,
    loading: Boolean,
    onSearch: (String) -> Unit,
    onSelect: (WorkspaceFileDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val tree = remember(files) { buildWorkspaceFileTree(files) }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    val visibleNodes = remember(tree, expanded) { flattenFileTree(tree, expanded) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("电脑文件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索当前任务目录") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { onSearch(query) }) {
                            Icon(Icons.Default.Search, "搜索")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    loading -> Box(
                        Modifier.fillMaxWidth().height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    files.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("没有可添加的文件", color = Color(0xFF777772)) }
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(visibleNodes, key = { it.node.path }) { item ->
                            val node = item.node
                            val file = node.file
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (file != null && !file.isDirectory) {
                                            if (file.attachable) onSelect(file)
                                        } else {
                                            expanded = if (node.path in expanded) expanded - node.path else expanded + node.path
                                        }
                                    }
                                    .padding(start = (item.depth * 18).dp, top = 9.dp, bottom = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (file == null) {
                                        if (node.path in expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight
                                    } else Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        node.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (file == null) FontWeight.SemiBold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (file != null && !file.isDirectory) Text(
                                        "${file.relativePath} · ${formatBytes(file.size)}" +
                                            if (file.attachable) "" else " · 仅展示",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF666661),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            HorizontalDivider(color = Color(0xFFE5E5E0))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private data class VisibleFileTreeNode(val node: FileTreeNode, val depth: Int)

private fun flattenFileTree(
    roots: List<FileTreeNode>,
    expanded: Set<String>,
): List<VisibleFileTreeNode> {
    val output = mutableListOf<VisibleFileTreeNode>()
    fun visit(nodes: List<FileTreeNode>, depth: Int) {
        nodes.forEach { node ->
            output += VisibleFileTreeNode(node, depth)
            if (node.file == null && node.path in expanded) visit(node.children, depth + 1)
        }
    }
    visit(roots, 0)
    return output
}

@Composable
private fun AttachmentPreviewRow(
    attachments: List<ComposerAttachment>,
    onRemove: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.localId }) { attachment ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.widthIn(min = 120.dp, max = 210.dp),
            ) {
                Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (attachment.mimeType.startsWith("image/") && attachment.uri.isNotBlank()) {
                        AndroidView(
                            factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
                            update = { it.setImageURI(Uri.parse(attachment.uri)) },
                            modifier = Modifier.size(38.dp),
                        )
                    } else {
                        Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        Text(
                            when (attachment.uploadState) {
                                AttachmentUploadState.UPLOADING -> "正在上传"
                                AttachmentUploadState.READY -> formatBytes(attachment.size)
                                AttachmentUploadState.FAILED -> attachment.error ?: "上传失败"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (attachment.uploadState == AttachmentUploadState.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRemove(attachment.localId) }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Close, "移除附件", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

private fun formatBytes(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
    size >= 1024 -> "%.1f KB".format(size / 1024.0)
    else -> "$size B"
}
