package com.alphapi.codexremote

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class WorkspaceUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectsAndSubmitsAUserInputOption() {
        var submitted: Map<String, List<String>>? = null
        val request = ApprovalDto(
            requestId = "request-1",
            threadId = "thread-1",
            method = "item/tool/requestUserInput",
            expiresAt = Long.MAX_VALUE,
            payload = buildJsonObject {
                put("params", buildJsonObject {
                    put("questions", buildJsonArray {
                        add(buildJsonObject {
                            put("id", "entity")
                            put("header", "主体类型")
                            put("question", "后台登记的主体是哪一种？")
                            put("options", buildJsonArray {
                                add(buildJsonObject { put("label", "个体工商户") })
                                add(buildJsonObject { put("label", "企业") })
                            })
                        })
                    })
                })
            },
        )
        compose.setContent {
            MaterialTheme {
                UserInputDialog(
                    request = request,
                    onDismiss = {},
                    onSubmit = { submitted = it },
                )
            }
        }

        compose.onNodeWithText("后台登记的主体是哪一种？").assertIsDisplayed()
        compose.onNodeWithText("个体工商户").performClick()
        compose.onNodeWithText("提交回答").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("个体工商户"), submitted?.get("entity"))
        }
    }

    @Test
    fun disconnectedTaskListDoesNotShowTheLoadingSpinner() {
        compose.setContent {
            MaterialTheme {
                ProjectTaskList(
                    groups = emptyList(),
                    selectedKey = ProjectGroup.ALL_KEY,
                    selectedThreadId = null,
                    connected = false,
                    loading = true,
                    onTaskClick = {},
                )
            }
        }

        compose.onAllNodesWithTag("task-list-loading").assertCountEquals(0)
        compose.onNodeWithText("Bridge 未连接").assertIsDisplayed()
    }

    @Test
    fun showsAStableRunningIndicatorOnlyForActiveTasks() {
        compose.setContent {
            MaterialTheme {
                ProjectTaskList(
                    groups = listOf(
                        ProjectGroup(
                            key = "project",
                            name = "Project",
                            cwd = "C:\\Project",
                            tasks = listOf(
                                TaskDto("active", "运行中", "active", 1, 0, true),
                                TaskDto("idle", "已完成", "idle", 1, 0, true),
                            ),
                        ),
                    ),
                    selectedKey = ProjectGroup.ALL_KEY,
                    selectedThreadId = null,
                    onTaskClick = {},
                )
            }
        }

        compose.onNodeWithTag("task-running:active").assertIsDisplayed()
        compose.onAllNodesWithTag("task-running:idle").assertCountEquals(0)
    }

    @Test
    fun longPressingATaskShowsConversationActions() {
        var resyncedThreadId: String? = null
        compose.setContent {
            MaterialTheme {
                ProjectTaskList(
                    groups = listOf(
                        ProjectGroup(
                            key = "project",
                            name = "Project",
                            cwd = "C:\\Project",
                            tasks = listOf(TaskDto("thread-1", "测试会话", "idle", 1, 0, true)),
                        ),
                    ),
                    selectedKey = ProjectGroup.ALL_KEY,
                    selectedThreadId = null,
                    canOpenOnDesktop = true,
                    onTaskClick = {},
                    onResyncTask = { resyncedThreadId = it.threadId },
                )
            }
        }

        compose.onNodeWithTag("task-card:thread-1").performTouchInput { longClick() }
        compose.onNodeWithTag("task-menu:thread-1").assertIsDisplayed()
        compose.onNodeWithText("打开会话").assertIsDisplayed()
        compose.onNodeWithText("在桌面端打开").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("重新同步会话").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("thread-1", resyncedThreadId) }
    }

    @Test
    fun showsTheUserSelectedLanRouteInTheDrawer() {
        compose.setContent {
            MaterialTheme {
                ProjectDrawerContent(
                    groups = emptyList(),
                    selectedKey = ProjectGroup.ALL_KEY,
                    activeServerUrl = "http://192.168.1.20:8766",
                    onSelect = {},
                    onManageConnections = {},
                    onAbout = {},
                    useSystemRoute = false,
                )
            }
        }

        compose.onNodeWithText("通过VPN/代理").assertIsDisplayed()
        compose.onNodeWithTag("system-route-switch").assertIsDisplayed().assertIsOff()
        compose.onAllNodesWithText("关闭后仅当前终端", substring = true).assertCountEquals(0)
    }

    @Test
    fun letsTheUserChooseTheRouteForATailscaleEndpoint() {
        compose.setContent {
            MaterialTheme {
                ProjectDrawerContent(
                    groups = emptyList(),
                    selectedKey = ProjectGroup.ALL_KEY,
                    activeServerUrl = "http://100.104.36.62:8766",
                    onSelect = {},
                    onManageConnections = {},
                    onAbout = {},
                    useSystemRoute = true,
                )
            }
        }

        compose.onNodeWithText("通过VPN/代理").assertIsDisplayed()
        compose.onNodeWithTag("system-route-switch").assertIsEnabled()
    }

    @Test
    fun placesRecentTasksBeforeAllTasks() {
        compose.setContent {
            MaterialTheme {
                ProjectDrawerContent(
                    groups = emptyList(),
                    selectedKey = OPEN_TASKS_KEY,
                    activeServerUrl = "http://192.168.1.20:8766",
                    onSelect = {},
                    onManageConnections = {},
                    onAbout = {},
                )
            }
        }

        val recent = compose.onNodeWithText("最近任务").fetchSemanticsNode().boundsInRoot
        val all = compose.onNodeWithText("所有任务").fetchSemanticsNode().boundsInRoot
        assertTrue("Recent tasks should appear first", recent.top < all.top)
    }

    @Test
    fun keepsUpdateAndProjectLinksInsideAbout() {
        var opened = false
        compose.setContent {
            MaterialTheme {
                ProjectDrawerContent(
                    groups = emptyList(),
                    selectedKey = OPEN_TASKS_KEY,
                    activeServerUrl = "http://192.168.1.20:8766",
                    onSelect = {},
                    onManageConnections = {},
                    onAbout = { opened = true },
                )
            }
        }

        compose.onNodeWithText("关于").assertIsDisplayed().performClick()
        compose.onAllNodesWithText("检查更新").assertCountEquals(0)
        compose.onAllNodesWithText("作者邮箱").assertCountEquals(0)
        compose.onAllNodesWithText("开源项目").assertCountEquals(0)
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun aboutDialogProvidesTheHiddenSecondaryActions() {
        var checkedUpdates = false
        var openedEmail = false
        var openedSource = false
        compose.setContent {
            MaterialTheme {
                AboutDialog(
                    onDismiss = {},
                    onCheckUpdates = { checkedUpdates = true },
                    onOpenAuthorEmail = { openedEmail = true },
                    onOpenSource = { openedSource = true },
                )
            }
        }

        compose.onNodeWithText("当前版本", substring = true).assertIsDisplayed()
        compose.onNodeWithText("检查更新").performClick()
        compose.onNodeWithText("作者邮箱").performClick()
        compose.onNodeWithText("开源项目").performClick()
        compose.runOnIdle {
            assertTrue(checkedUpdates)
            assertTrue(openedEmail)
            assertTrue(openedSource)
        }
    }

    @Test
    fun explainsWhyDesktopTaskCreationIsUnavailable() {
        compose.setContent {
            MaterialTheme {
                NewTaskDialog(
                    enabled = false,
                    groups = emptyList(),
                    preferredProjectKey = ProjectGroup.ALL_KEY,
                    models = emptyList(),
                    creating = false,
                    onDismiss = {},
                    onCreate = {},
                )
            }
        }

        compose.onNodeWithText("当前连接暂不支持远程新建任务。请先在电脑上新建并打开任务。")
            .assertIsDisplayed()
        compose.onNodeWithText("创建").assertIsNotEnabled()
    }

    @Test
    fun showsPartialCreationErrorWithoutDiscardingThePromptField() {
        compose.setContent {
            MaterialTheme {
                NewTaskDialog(
                    enabled = true,
                    groups = listOf(ProjectGroup("project", "Demo", "C:\\Demo", emptyList())),
                    preferredProjectKey = "project",
                    models = listOf(
                        ModelOptionDto(
                            id = "gpt-test",
                            displayName = "GPT Test",
                            supportedReasoningEfforts = listOf(ReasoningEffortDto("medium")),
                            defaultReasoningEffort = "medium",
                        ),
                    ),
                    creating = false,
                    creationError = "首条指令没有被 Codex 接收。你的指令仍保留在此窗口。",
                    onDismiss = {},
                    onCreate = {},
                )
            }
        }

        compose.onNodeWithText("首条指令没有被 Codex 接收", substring = true).assertIsDisplayed()
        compose.onNodeWithText("任务指令").assertIsDisplayed()
    }

    @Test
    fun showsAParsedFullDiffAndCopyAction() {
        compose.setContent {
            MaterialTheme {
                DiffViewerDialog(
                    taskTitle = "Demo",
                    rawDiff = """diff --git a/a.kt b/a.kt
--- a/a.kt
+++ b/a.kt
@@ -1 +1 @@
-old
+new""",
                    loading = false,
                    onDismiss = {},
                )
            }
        }

        compose.onAllNodesWithText("a.kt").assertCountEquals(2)
        compose.onNodeWithText("+new").assertIsDisplayed()
        compose.onNodeWithContentDescription("复制完整 diff").assertIsDisplayed()
    }

    @Test
    fun keepsEveryApprovalDecisionVisibleOnNarrowScreens() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(280.dp)) {
                    ApprovalDecisionActions(
                        enabled = true,
                        onAccept = {},
                        onDecline = {},
                        onCancel = {},
                    )
                }
            }
        }

        compose.onNodeWithText("仅本次允许").assertIsDisplayed()
        compose.onNodeWithText("拒绝").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test
    fun showsUserNamedServerAddresses() {
        compose.setContent {
            MaterialTheme {
                ConnectionManagerDialog(
                    state = RemoteState(
                        configured = true,
                        serverUrl = "http://100.100.1.2:8766",
                        activeConnectionId = "remote",
                        serverAddresses = listOf(
                            SavedServerAddress("远程电脑", "http://100.100.1.2:8766", "remote"),
                            SavedServerAddress("办公室电脑", "http://192.168.1.8:8766", "office"),
                        ),
                    ),
                    onDismiss = {},
                    onSwitch = {},
                    onPair = { _, _, _, _ -> },
                    onEdit = { _, _, _, _ -> },
                    onRemove = {},
                    onClearPairing = {},
                )
            }
        }

        compose.onNodeWithText("远程电脑").assertIsDisplayed()
        compose.onNodeWithText("办公室电脑").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换到 远程电脑").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换到 办公室电脑").assertIsDisplayed()
        compose.onNodeWithText("终端名称").assertIsDisplayed()
        compose.onNodeWithText("六位配对码").assertIsDisplayed()
    }

    @Test
    fun keepsNewAddressDraftUntilItAppearsInTheSavedList() {
        compose.setContent {
            MaterialTheme {
                ConnectionManagerDialog(
                    state = RemoteState(
                        configured = true,
                        serverUrl = "http://100.100.1.2:8766",
                        activeConnectionId = "remote",
                        serverAddresses = listOf(
                            SavedServerAddress("远程电脑", "http://100.100.1.2:8766", "remote"),
                        ),
                    ),
                    onDismiss = {},
                    onSwitch = {},
                    onPair = { _, _, _, _ -> },
                    onEdit = { _, _, _, _ -> },
                    onRemove = {},
                    onClearPairing = {},
                )
            }
        }

        compose.onNodeWithTag("connection-name-input").performTextInput("工作室")
        compose.onNodeWithTag("connection-url-input").performTextInput("192.168.1.8:8766")
        compose.onNodeWithTag("connection-code-input").performTextInput("123456")
        compose.onNodeWithText("配对并切换").performClick()

        compose.onNodeWithTag("connection-name-input").assertTextContains("工作室")
        compose.onNodeWithTag("connection-url-input").assertTextContains("192.168.1.8:8766")
        compose.onNodeWithTag("connection-code-input").assertTextContains("123456")
    }

    @Test
    fun closesTerminalDialogAfterANewConnectionIsPaired() {
        compose.setContent {
            val visible = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(true)
            }
            if (visible.value) {
                MaterialTheme {
                    ConnectionManagerDialog(
                        state = RemoteState(),
                        onDismiss = { visible.value = false },
                        onSwitch = {},
                        onPair = { _, _, _, onSaved -> onSaved() },
                        onEdit = { _, _, _, _ -> },
                        onRemove = {},
                        onClearPairing = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("connection-url-input").performTextInput("192.168.1.8:8766")
        compose.onNodeWithTag("connection-code-input").performTextInput("123456")
        compose.onNodeWithText("配对并切换").performClick()

        compose.onAllNodesWithText("Codex 终端").assertCountEquals(0)
    }

    @Test
    fun editsASavedTerminalWithoutRequestingAnotherPairingCode() {
        compose.setContent {
            MaterialTheme {
                ConnectionManagerDialog(
                    state = RemoteState(
                        configured = true,
                        serverUrl = "http://100.100.1.2:8766",
                        activeConnectionId = "remote",
                        serverAddresses = listOf(
                            SavedServerAddress("远程电脑", "http://100.100.1.2:8766", "remote"),
                        ),
                    ),
                    onDismiss = {},
                    onSwitch = {},
                    onPair = { _, _, _, _ -> },
                    onEdit = { _, _, _, onSaved -> onSaved() },
                    onRemove = {},
                    onClearPairing = {},
                )
            }
        }

        compose.onNodeWithContentDescription("编辑终端 远程电脑").performClick()

        compose.onNodeWithText("编辑已保存终端：只更新名称或 IP，继续使用原授权。")
            .assertIsDisplayed()
        compose.onNodeWithTag("connection-name-input").assertTextContains("远程电脑")
        compose.onNodeWithTag("connection-url-input").assertTextContains("http://100.100.1.2:8766")
        compose.onAllNodesWithText("六位配对码").assertCountEquals(0)
        compose.onNodeWithText("保存地址").performClick()
        compose.onNodeWithText("添加新终端").assertIsDisplayed()
    }

    @Test
    fun modelMenuMatchesItsFieldWidth() {
        val models = listOf(
            "gpt-6-astra",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "gpt-5.6-luna",
            "gpt-5.5",
            "gpt-5.4",
            "gpt-5.4-mini",
            "gpt-5.2",
        ).map { id ->
            ModelOptionDto(
                id = id,
                displayName = id.uppercase(),
                supportedReasoningEfforts = listOf(ReasoningEffortDto("high")),
                defaultReasoningEffort = "high",
            )
        }
        compose.setContent {
            MaterialTheme {
                ThreadSettingsDialog(
                    task = TaskDto(
                        threadId = "thread-model-menu",
                        title = "Model",
                        status = "idle",
                        revision = 1,
                        pendingApprovals = 0,
                        settings = ThreadSettingsDto("gpt-5.6-sol", "high"),
                    ),
                    models = models,
                    saving = false,
                    onDismiss = {},
                    onSave = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("selector:模型").performClick()
        val fieldWidth = compose.onNodeWithTag("selector:模型").fetchSemanticsNode().boundsInRoot.width
        val menuWidth = compose.onNodeWithTag("selectorMenu:模型").fetchSemanticsNode().boundsInRoot.width
        assertTrue("menu width=$menuWidth field width=$fieldWidth", kotlin.math.abs(menuWidth - fieldWidth) <= 2f)
        compose.onNodeWithText("GPT-6-ASTRA").assertIsDisplayed()
    }
}
