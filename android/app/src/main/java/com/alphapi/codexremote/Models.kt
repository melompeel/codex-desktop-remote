package com.alphapi.codexremote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File

@Serializable
data class PairResponse(val deviceId: String, val token: String)

data class SavedServerAddress(
    val name: String,
    val serverUrl: String,
    val connectionId: String = serverUrl,
)

@Serializable
data class TaskDto(
    val threadId: String,
    val title: String,
    val status: String,
    val revision: Int,
    val pendingApprovals: Int,
    val ownerAvailable: Boolean = false,
    val cwd: String? = null,
    val cwdGroupKey: String? = null,
    val cwdGroupLabel: String? = null,
    val updatedAt: Long? = null,
    val settings: ThreadSettingsDto? = null,
    val activeTurnId: String? = null,
)

@Serializable
data class TasksResponse(val tasks: List<TaskDto>)

@Serializable
data class TimelineMediaDto(
    val mediaId: String,
    val name: String,
    val mimeType: String,
)

@Serializable
data class TimelineResourceDto(
    val resourceId: String,
    val name: String,
    val mimeType: String = "application/octet-stream",
)

@Serializable
data class TimelineItemDto(
    val id: String,
    val turnId: String,
    val kind: String,
    val text: String,
    val status: String? = null,
    val media: TimelineMediaDto? = null,
    val resources: List<TimelineResourceDto> = emptyList(),
    val sourceItemId: String? = null,
    val turnDurationMs: Long? = null,
)

@Serializable
data class TaskDetailDto(
    val threadId: String,
    val title: String,
    val status: String,
    val revision: Int,
    val items: List<TimelineItemDto> = emptyList(),
    val hasMoreHistory: Boolean = false,
    val historyCursor: String? = null,
    val cwd: String? = null,
    val cwdGroupKey: String? = null,
    val cwdGroupLabel: String? = null,
    val settings: ThreadSettingsDto? = null,
    val activeTurnId: String? = null,
)

@Serializable
data class TaskDetailResponse(val task: TaskDetailDto)

@Serializable
data class ApprovalDto(
    val requestId: String,
    val threadId: String,
    val method: String,
    val expiresAt: Long,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ApprovalsResponse(val approvals: List<ApprovalDto>)

@Serializable
data class BridgeEvent(
    val sequence: Long,
    val type: String,
    val threadId: String? = null,
    val createdAt: Long,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class CompatibilityDto(
    val supported: Boolean,
    val verified: Boolean = true,
    val mode: String = if (verified) "verified" else "best-effort",
    val expected: String,
    val installed: String? = null,
)

@Serializable
data class HealthDto(
    val ok: Boolean,
    val ipc: String,
    val compatibility: CompatibilityDto,
)

@Serializable
data class ReasoningEffortDto(
    val reasoningEffort: String,
    val description: String = reasoningEffort,
)

@Serializable
data class ModelOptionDto(
    val id: String,
    val displayName: String = id,
    val description: String = "",
    val supportedReasoningEfforts: List<ReasoningEffortDto> = emptyList(),
    val defaultReasoningEffort: String? = null,
    val hidden: Boolean = false,
    val isDefault: Boolean = false,
)

@Serializable
data class ModelsResponse(
    val models: List<ModelOptionDto> = emptyList(),
    val data: List<ModelOptionDto> = emptyList(),
) {
    fun values(): List<ModelOptionDto> = (models.ifEmpty { data }).filterNot { it.hidden }
}

@Serializable
data class AttachmentCapabilitiesDto(
    val enabled: Boolean = false,
    val kinds: List<String> = emptyList(),
    val queued: Boolean = false,
    val maxBytes: Long = 10L * 1024 * 1024,
)

@Serializable
data class RemoteCapabilitiesDto(
    val apiVersion: String = "v1",
    val writable: Boolean = false,
    val taskCreation: Boolean = false,
    val taskActivation: Boolean = false,
    val deliveries: List<String> = listOf("auto"),
    val queue: Boolean = false,
    val modelSettings: Boolean = false,
    val diff: Boolean = false,
    val attachments: AttachmentCapabilitiesDto = AttachmentCapabilitiesDto(),
) {
    val explicitDelivery: Boolean get() = "start" in deliveries && "steer" in deliveries
    val threadSettings: Boolean get() = modelSettings
    val newTask: Boolean get() = taskCreation
    val maxAttachmentBytes: Long get() = attachments.maxBytes
}

@Serializable
data class CapabilitiesResponse(
    val capabilities: RemoteCapabilitiesDto = RemoteCapabilitiesDto(),
)

enum class DeliveryMode(val wireValue: String) {
    START("start"),
    STEER("steer"),
    QUEUE("queue"),
}

@Serializable
data class QueuedFollowUpDto(
    val id: String,
    val text: String,
    val createdAt: Long? = null,
    val attachmentIds: List<String> = emptyList(),
)

@Serializable
data class QueueResponse(
    val queue: ThreadQueueDto = ThreadQueueDto(),
) {
    fun values(): List<QueuedFollowUpDto> = queue.messages
    val hash: String get() = queue.hash
}

@Serializable
data class ThreadQueueDto(
    val threadId: String = "",
    val hash: String = "",
    val messages: List<QueuedFollowUpDto> = emptyList(),
)

@Serializable
data class TaskDiffTurnDto(
    val turnId: String,
    val status: String = "",
    val unifiedDiff: String,
)

@Serializable
data class TaskDiffFileDto(
    val turnId: String,
    val itemId: String,
    val path: String = "",
    val kind: String = "update",
    val unifiedDiff: String,
)

@Serializable
data class TaskDiffDto(
    val threadId: String = "",
    val revision: Int = 0,
    val turns: List<TaskDiffTurnDto> = emptyList(),
    val files: List<TaskDiffFileDto> = emptyList(),
) {
    fun unifiedDiff(): String = (files.map { it.unifiedDiff }.ifEmpty { turns.map { it.unifiedDiff } })
        .filter(String::isNotBlank)
        .joinToString("\n")
}

@Serializable
data class DiffResponse(
    val diff: TaskDiffDto = TaskDiffDto(),
)

@Serializable
data class UploadedAttachmentDto(
    val attachmentId: String,
    val name: String,
    val mimeType: String = "application/octet-stream",
    val size: Long = 0,
)

@Serializable
data class UploadAttachmentResponse(
    val attachment: UploadedAttachmentDto,
)

@Serializable
data class WorkspaceFileDto(
    val relativePath: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val attachable: Boolean = true,
    val isDirectory: Boolean = false,
)

@Serializable
data class WorkspaceFilesResponse(val files: List<WorkspaceFileDto> = emptyList())

@Serializable
data class ThreadSettingsDto(
    val model: String? = null,
    val effort: String? = null,
    val serviceTier: String? = null,
)

enum class AttachmentUploadState { UPLOADING, READY, FAILED }

data class ComposerAttachment(
    val localId: String,
    val uri: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val uploadState: AttachmentUploadState,
    val attachmentId: String? = null,
    val error: String? = null,
)

data class CreateTaskDraft(
    val mode: String = "project",
    val projectKey: String? = null,
    val cwd: String? = null,
    val prompt: String,
    val model: String?,
    val reasoningEffort: String?,
)

@Serializable
data class CreateTaskResponse(
    val task: TaskDto? = null,
    val threadId: String? = null,
    val promptAccepted: Boolean? = null,
    val stage: String? = null,
    val error: String? = null,
)

internal data class TaskCreationDisposition(
    val closeDialog: Boolean,
    val message: String? = null,
)

internal fun CreateTaskResponse.disposition(): TaskCreationDisposition {
    val accepted = promptAccepted ?: (task != null)
    if (accepted) return TaskCreationDisposition(closeDialog = true)

    val summary = when (stage) {
        "owner" -> "任务已创建，但 Codex Desktop 尚未接管，首条指令没有发送。"
        "settings" -> "任务已创建，但模型或推理设置没有完成，首条指令没有发送。"
        "prompt" -> "任务已创建，但首条指令没有被 Codex 接收。"
        else -> "任务只完成了部分创建步骤，首条指令没有发送。"
    }
    val detail = error?.trim()?.takeIf(String::isNotEmpty)?.let { "\n$it" }.orEmpty()
    return TaskCreationDisposition(
        closeDialog = false,
        message = "$summary 你的指令仍保留在此窗口，可检查桌面端后重试。$detail",
    )
}

data class RemoteState(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val connectionEstablished: Boolean = false,
    val loading: Boolean = false,
    val taskListLoading: Boolean = false,
    val serverUrl: String = "",
    val activeConnectionId: String = "",
    val connectionRouteMode: ConnectionRouteMode = ConnectionRouteMode.SYSTEM,
    val serverAddresses: List<SavedServerAddress> = emptyList(),
    val tasks: List<TaskDto> = emptyList(),
    val taskDetail: TaskDetailDto? = null,
    val approvals: List<ApprovalDto> = emptyList(),
    val completedReviewThreadIds: Set<String> = emptySet(),
    val selectedThreadId: String? = null,
    val writeSupported: Boolean = true,
    val compatibilityVerified: Boolean = true,
    val ipcConnected: Boolean = false,
    val desktopVersion: String? = null,
    val capabilities: RemoteCapabilitiesDto = RemoteCapabilitiesDto(),
    val models: List<ModelOptionDto> = emptyList(),
    val queueByThread: Map<String, List<QueuedFollowUpDto>> = emptyMap(),
    val queueHashByThread: Map<String, String?> = emptyMap(),
    val draftsByThread: Map<String, String> = emptyMap(),
    val deliveryByThread: Map<String, DeliveryMode> = emptyMap(),
    val attachmentsByThread: Map<String, List<ComposerAttachment>> = emptyMap(),
    val taskMediaById: Map<String, File> = emptyMap(),
    val loadingTaskMediaIds: Set<String> = emptySet(),
    val failedTaskMediaIds: Set<String> = emptySet(),
    val downloadingResourceIds: Set<String> = emptySet(),
    val workspaceFiles: List<WorkspaceFileDto> = emptyList(),
    val workspaceFilesLoading: Boolean = false,
    val sendingThreads: Set<String> = emptySet(),
    val stoppingThreads: Set<String> = emptySet(),
    val activatingThreads: Set<String> = emptySet(),
    val settingsThreads: Set<String> = emptySet(),
    val diffByThread: Map<String, String> = emptyMap(),
    val loadingDiffThreads: Set<String> = emptySet(),
    val creatingTask: Boolean = false,
    val taskCreationError: String? = null,
    val loadingOlderHistoryThreads: Set<String> = emptySet(),
    val loadingAllHistoryThreads: Set<String> = emptySet(),
    val historyLoadProgressByThread: Map<String, Int> = emptyMap(),
    val historyLoadErrorThreads: Set<String> = emptySet(),
    val syncingThreadIds: Set<String> = emptySet(),
    val error: String? = null,
) {
    val pendingTaskCount: Int
        get() = (completedReviewThreadIds + approvals.map { it.threadId }).size
}
