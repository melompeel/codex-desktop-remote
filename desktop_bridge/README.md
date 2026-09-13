# Windows Desktop Bridge

Bridge 运行在 Codex Desktop 同一台 Windows 电脑上。它用私有 `codex-ipc` 跟随当前桌面任务，并向 Android 提供版本化 `/v1` HTTP/WebSocket 接口。

## 运行要求

- Windows 11
- Codex Desktop（已验证版本为 `26.901.1978.0`）
- Desktop bundled `codex-cli`（已验证版本为 `0.153.0-alpha.5`）
- Node.js 22 或更新版本
- Android 手机通过可信局域网或同一 Tailscale tailnet 连接电脑

普通使用请直接运行 GitHub Release 中 Windows 包的 `CodexRemoteManager.exe`。它会在图形界面中显示 Bridge/IPC 状态、配对码、IP、端口、Tailscale/LAN 地址，并提供启动、停止、重启、托盘后台运行和登录自启动。

以下脚本仅作为源码开发和故障排查后备：

```powershell
.\start-bridge.ps1
```

直接再次运行 `start-bridge.ps1` 时，脚本会先通过健康接口和进程命令行确认 `8766` 的占用者确实是本项目 Bridge，然后停止旧进程并重新启动。其他程序占用端口时不会被终止。重启只轮换临时配对码，不会删除 `%APPDATA%\OneSCodexRemote\devices.json` 中已有的设备令牌。

`start-bridge.ps1` 会占用当前 PowerShell 窗口，关闭窗口会停止 Bridge。已完成配对后可双击 `start-bridge-background.cmd`，它会安全重启现有 Bridge 并在隐藏窗口中继续运行。需要查看新设备配对码或排查日志时，再使用前台模式。

日常可直接双击 `launch-codex-remote.cmd`。它会打开 Codex Desktop（若尚未运行）并启动 Bridge；若 `8766` 上已经是健康的 Bridge，则不会重复启动。

需要 Windows 登录后静默自启时，运行一次：

```powershell
.\install-autostart.ps1
```

Bridge 可以先于 Codex Desktop 启动，并会自动重连 `codex-ipc`。因此之后打开 Codex Desktop 时无需再次启动 Bridge。撤销自启使用 `remove-autostart.ps1`。自启不等于开放公网端口，也不会创建防火墙规则。

默认监听 `0.0.0.0:8766`。终端会显示可用的 LAN 地址和十分钟有效的六位配对码。设备记录保存在 `%APPDATA%\OneSCodexRemote\devices.json`，其中只有令牌哈希，没有明文令牌。

Windows 管理器还会使用仅允许真实回环连接访问的 `/v1/local/*` 接口读取状态、轮换临时配对码和撤销指定设备。该接口不会接受局域网或 Tailscale 请求；轮换配对码和重启 Bridge 都不会撤销已有设备令牌，只有用户在设备下拉列表中确认删除的设备会失效。

脚本不会改 Windows 防火墙。如果手机无法访问，而网络配置为“专用网络”，可在管理员 PowerShell 中手动放行：

```powershell
New-NetFirewallRule -DisplayName "Codex Remote Bridge" -Direction Inbound -Protocol TCP -LocalPort 8766 -Action Allow -Profile Private
```

不要为公用网络放行，不要配置路由器端口转发。

## API

- `GET /v1/health`：IPC、桌面版本和可选 ASR 状态。
- `POST /v1/pair`：六位码配对，成功后立即轮换配对码。
- `GET /v1/tasks`、`GET /v1/tasks/:id`：任务列表与精简时间线。
- `GET /v1/tasks/:id/media/:mediaId`：读取该任务明确展示的本地图片。
- `GET /v1/tasks/:id/resources/:resourceId`：下载该任务回复中明确引用的本地文件。
- `GET /v1/tasks/:id/workspace-files`：列出当前任务工作目录的文件树数据；目录行使用 `isDirectory`，文件的 `attachable` 字段标记是否可以作为附件导入。
- `POST /v1/tasks/:id/workspace-attachments`：把选中的工作区文件复制成设备隔离附件。
- `POST /v1/tasks`：安全创建并交给 Codex Desktop 接管的新任务。
- `POST /v1/tasks/:id/activate`：验证历史任务后，通过 Windows 深链让 Codex Desktop 载入并接管。
- `GET /v1/capabilities`、`GET /v1/models`：功能开关和 Desktop 动态模型列表。
- `PATCH /v1/tasks/:id/settings`：更新下一轮使用的模型和推理强度。
- `GET /v1/tasks/:id/diff`：按轮次和文件读取完整 unified diff。
- `POST /v1/tasks/:id/follow`：向当前 Desktop owner 请求完整 snapshot。
- `POST /v1/tasks/:id/messages`：显式 start、steer、queue，省略时保留旧版自动行为。
- `GET /v1/tasks/:id/queue`、`DELETE /v1/tasks/:id/queue/:messageId`：带哈希并发保护的排队消息。
- `POST /v1/tasks/:id/interrupt`：停止活动轮次。
- `POST /v1/attachments`、`DELETE /v1/attachments/:id`：设备隔离的图片和安全文件附件。
- `GET /v1/approvals`、`POST /v1/approvals/:id`：读取与处理审批。
- `POST /v1/user-input/:id`：整批提交用户问题答案。
- `POST /v1/tasks/:id/request-push`：发送固定的受控 Git 工作流指令。
- `DELETE /v1/devices/self`：撤销当前设备凭据。
- `GET /v1/stream`：WebSocket 事件流，每条事件带递增 sequence。

除健康检查和配对外，所有请求都要求 Bearer 令牌以及 `X-Request-Id`、`X-Timestamp`、`X-Signature`。签名内容为：

```text
METHOD
/path?query
unix_timestamp_seconds
request_id
sha256_hex(raw_body)
```

签名只接受电脑时间前后 60 秒内的请求，并用唯一请求 ID 阻止有效期内重放。
完整请求与响应结构见 [API_CONTRACT.md](./API_CONTRACT.md)。

## 开发验证

```powershell
npm ci
npm test
npm run check
npm run build
```

常驻 `app-server` 只用于读取任务目录和历史；所有实际用户任务写操作都必须先通过 `thread-owner-discovery` 找到当前 Codex Desktop owner。手机主动打开历史任务时，Bridge 只允许为目录中已存在的 thread ID 生成 `codex://threads/:id` 深链，并等待 Desktop owner；不接受任意 URL。Bridge 每次激活时都通过 `Get-AppxPackageManifest` 读取当前 Codex Desktop 清单声明的主程序（当前版本为 `ChatGPT.exe`），不写死桌面版本号或入口文件名；定位或直接启动失败时才回退到 Windows 的 `codex://` 协议处理器。创建任务时，一次性 helper 只生成并回滚引导轮次，确认留下零轮次任务后立即退出；用户提示词只会在 Desktop 接管后通过 IPC 发送。

已知协议版本记录在 `src/ipc/adapter.ts`。桌面包或内嵌 CLI 升级后，Bridge 会继续尝试已知协议；版本不同只产生诊断告警，不会整体切成只读。未知版本的 stream 若仍符合已知结构会继续同步，具体操作若被新版协议拒绝则只向客户端返回该操作错误。升级后仍应尽快重新验证并更新版本常量。

## 可选 Whisper

Android 端使用系统输入法语音，不依赖 Whisper。Bridge 仍保留 `/v1/voice`，只有显式设置 `WHISPER_SERVER_URL` 时启用，例如：

```powershell
$env:WHISPER_SERVER_URL = "http://127.0.0.1:8178/inference"
.\start-bridge.ps1
```

Whisper 服务必须只监听回环地址。
