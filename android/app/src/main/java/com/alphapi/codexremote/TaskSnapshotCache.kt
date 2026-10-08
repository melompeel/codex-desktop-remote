package com.alphapi.codexremote

import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class TaskSnapshotCache(
    private val root: File,
    private val maxBytes: Long = 50L * 1024 * 1024,
    private val maxThreadsPerConnection: Int = 32,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun loadTasks(connectionKey: String): List<TaskDto> =
        read<CachedTasks>(tasksFile(connectionKey))?.tasks.orEmpty()

    @Synchronized
    fun saveTasks(connectionKey: String, tasks: List<TaskDto>) {
        write(tasksFile(connectionKey), CachedTasks(tasks = tasks))
        trim()
    }

    @Synchronized
    fun loadDetail(connectionKey: String, threadId: String): TaskDetailDto? =
        read<CachedDetail>(detailFile(connectionKey, threadId))?.detail

    @Synchronized
    fun saveDetail(connectionKey: String, detail: TaskDetailDto) {
        write(detailFile(connectionKey, detail.threadId), CachedDetail(detail = detail))
        trimConnection(connectionDirectory(connectionKey))
        trim()
    }

    @Synchronized
    fun clearDetail(connectionKey: String, threadId: String) {
        detailFile(connectionKey, threadId).delete()
    }

    @Synchronized
    fun clearConnection(connectionKey: String) {
        connectionDirectory(connectionKey).deleteRecursively()
    }

    @Synchronized
    fun clearAll() {
        root.deleteRecursively()
    }

    private inline fun <reified T> read(file: File): T? = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_COMPRESSED_ENTRY_BYTES) return@runCatching null
        val value = GZIPInputStream(file.inputStream().buffered()).bufferedReader().use { reader ->
            json.decodeFromString<T>(reader.readText())
        }
        file.setLastModified(System.currentTimeMillis())
        value
    }.getOrNull()

    private inline fun <reified T> write(file: File, value: T) {
        file.parentFile?.mkdirs()
        val pending = File(file.parentFile, "${file.name}.tmp")
        try {
            GZIPOutputStream(pending.outputStream().buffered()).bufferedWriter().use { writer ->
                writer.write(json.encodeToString(value))
            }
            check(pending.length() in 1..MAX_COMPRESSED_ENTRY_BYTES) { "task-cache-entry-too-large" }
            if (file.exists()) check(file.delete()) { "task-cache-replace-failed" }
            check(pending.renameTo(file)) { "task-cache-write-failed" }
        } finally {
            pending.delete()
        }
    }

    private fun tasksFile(connectionKey: String) = File(connectionDirectory(connectionKey), "tasks.json.gz")

    private fun detailFile(connectionKey: String, threadId: String) =
        File(File(connectionDirectory(connectionKey), "threads"), "${digest(threadId)}.json.gz")

    private fun connectionDirectory(connectionKey: String) = File(root, digest(connectionKey))

    private fun trimConnection(directory: File) {
        val details = File(directory, "threads").listFiles()
            ?.filter(File::isFile)
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        details.drop(maxThreadsPerConnection).forEach(File::delete)
    }

    private fun trim() {
        val files = root.walkTopDown().filter(File::isFile).sortedByDescending(File::lastModified).toList()
        var total = files.sumOf(File::length)
        files.asReversed().forEach { file ->
            if (total <= maxBytes) return
            total -= file.length()
            file.delete()
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    @Serializable
    private data class CachedTasks(
        val schema: Int = CACHE_SCHEMA,
        val tasks: List<TaskDto>,
    )

    @Serializable
    private data class CachedDetail(
        val schema: Int = CACHE_SCHEMA,
        val detail: TaskDetailDto,
    )

    companion object {
        private const val CACHE_SCHEMA = 1
        private const val MAX_COMPRESSED_ENTRY_BYTES = 12L * 1024 * 1024
    }
}
