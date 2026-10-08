package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskSnapshotCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun restoresTasksAndDetailsWithoutMixingConnections() {
        val cache = TaskSnapshotCache(temporary.root)
        val firstTask = task("thread-a", "First")
        val secondTask = task("thread-b", "Second")
        cache.saveTasks("computer-a", listOf(firstTask))
        cache.saveDetail("computer-a", detail(firstTask))
        cache.saveTasks("computer-b", listOf(secondTask))

        assertEquals(listOf("thread-a"), cache.loadTasks("computer-a").map { it.threadId })
        assertEquals("First reply", cache.loadDetail("computer-a", "thread-a")?.items?.single()?.text)
        assertEquals(listOf("thread-b"), cache.loadTasks("computer-b").map { it.threadId })
        assertNull(cache.loadDetail("computer-b", "thread-a"))
    }

    @Test
    fun trimsOldThreadSnapshotsAndClearsOneConnection() {
        val cache = TaskSnapshotCache(temporary.root, maxThreadsPerConnection = 2)
        (1..3).forEach { index ->
            cache.saveDetail("computer", detail(task("thread-$index", "Task $index")))
            Thread.sleep(5)
        }

        assertNull(cache.loadDetail("computer", "thread-1"))
        assertTrue(cache.loadDetail("computer", "thread-3") != null)
        cache.clearConnection("computer")
        assertTrue(cache.loadTasks("computer").isEmpty())
        assertNull(cache.loadDetail("computer", "thread-3"))
    }

    @Test
    fun clearsOnlyTheRequestedThreadDetail() {
        val cache = TaskSnapshotCache(temporary.root)
        val firstTask = task("thread-a", "First")
        val secondTask = task("thread-b", "Second")
        cache.saveTasks("computer", listOf(firstTask, secondTask))
        cache.saveDetail("computer", detail(firstTask))
        cache.saveDetail("computer", detail(secondTask))

        cache.clearDetail("computer", "thread-a")

        assertNull(cache.loadDetail("computer", "thread-a"))
        assertEquals("Second reply", cache.loadDetail("computer", "thread-b")?.items?.single()?.text)
        assertEquals(listOf("thread-a", "thread-b"), cache.loadTasks("computer").map { it.threadId })
    }

    private fun task(id: String, title: String) = TaskDto(
        threadId = id,
        title = title,
        status = "idle",
        revision = 1,
        pendingApprovals = 0,
    )

    private fun detail(task: TaskDto) = TaskDetailDto(
        threadId = task.threadId,
        title = task.title,
        status = task.status,
        revision = task.revision,
        items = listOf(TimelineItemDto("reply", "turn", "assistant", "${task.title} reply")),
    )
}
