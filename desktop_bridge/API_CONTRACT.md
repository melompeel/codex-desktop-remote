# Android Bridge API Contract

This document describes the additive `v1` contract used by the Android client. Existing
clients may continue to omit the new message fields and use `delivery: "auto"`.

## Authentication

Every route except `GET /v1/health`, `POST /v1/pair`, and the loopback-only
`/v1/local/*` manager routes requires:

- `Authorization: Bearer <device-token>`
- `X-Request-Id: <unique-id>`
- `X-Timestamp: <unix-seconds>`
- `X-Signature: <hex-hmac-sha256>`

The HMAC input is `METHOD`, the exact path including its encoded query string, timestamp,
request ID, and the SHA-256 hash of the raw body, joined with line feeds. Query order and
percent encoding must be identical in the request and signature input.

## Windows manager

The Windows manager uses three endpoints that accept connections only when the real
socket peer is `127.0.0.1` or `::1`; proxy headers are ignored:

- `GET /v1/local/status` returns the Bridge PID, listen host and port, Desktop/runtime
  versions, LAN and Tailscale addresses, IPC state, temporary pairing code, expiry, and
  paired-device summaries.
- `POST /v1/local/pairing/rotate` replaces only the temporary code. Existing device
  credentials remain valid.
- `DELETE /v1/local/devices/:deviceId` revokes one paired device without affecting the
  others.
- `POST /v1/local/shutdown` requests a graceful Bridge shutdown.

## Capabilities and models

`GET /v1/capabilities` returns `{ "capabilities": { ... } }`. `taskCreation` is true
when the known Desktop protocol operations and safe task materializer are available.
`taskActivation` is true when the Bridge can validate a historical task and ask Windows
to open it in Codex Desktop.
Attachments report `image` and `file`, a 10 MiB limit, and `queued: false`.

`GET /v1/models` returns `{ "models": [...] }`. Model rows use the Desktop app-server
fields `id`, `displayName`, `description`, `defaultReasoningEffort`,
`supportedReasoningEfforts`, `inputModalities`, `isDefault`, and `serviceTiers` when
present. Each supported effort uses `{ "reasoningEffort": "...", "description": "..." }`.
Use `GET /v1/models?refresh=true` when opening a model selector to bypass the 30-second
cache and refresh an idle catalog process. Background refreshes omit this parameter.
The Bridge reads all model pages and periodically renews the read-only helper using
the current installed runtime; it does not contain a fixed model allowlist.

## Tasks

`GET /v1/tasks` preserves the legacy `{ "tasks": [...] }` response. Supplying any of
`query`, `archived`, `cursor`, or `limit` returns:

```json
{
  "tasks": [],
  "nextCursor": null
}
```

Task summaries and `GET /v1/tasks/:threadId` may include:

```json
{
  "cwd": "C:\\repo",
  "cwdGroupKey": "c:/repo",
  "cwdGroupLabel": "repo",
  "gitInfo": {
    "branch": "main",
    "repositoryRoot": "C:\\repo",
    "sha": "abc123",
    "isDirty": false
  },
  "settings": {
    "model": "model-id",
    "effort": "medium",
    "serviceTier": "priority"
  },
  "activeTurnId": "turn-id"
}
```

Task summaries also include `updatedAt` in Unix milliseconds when the Desktop
provides it, allowing clients to detect work completed while disconnected.

`POST /v1/tasks/:threadId/activate` accepts a stable `idempotencyKey`. The Bridge first
confirms that the task exists in the local catalog, opens only the validated
`codex://threads/:threadId` deep link, and waits for Codex Desktop to become owner.
Concurrent activation attempts for the same task are coalesced. A successful response is:

```json
{ "ok": true, "ownerAvailable": true, "alreadyOpen": false }
```

The endpoint never accepts an arbitrary URL. Owner handoff timeout leaves the catalog
history readable and returns a service-unavailable error.

Timeline `ImageView` entries and assistant Markdown images use `kind: "image"`; images attached to
user messages use `kind: "userImage"`. Both include an opaque `mediaId`.
Timeline entries may also include `sourceItemId` and `turnDurationMs`. Clients use these
additive fields to keep the final rich response together and label a collapsed turn process.
When the compact timeline exceeds its item limit, user messages, user images, and each turn's
final response are retained before intermediate process entries are trimmed.
Fetch their bytes from `GET /v1/tasks/:threadId/media/:mediaId`. Local Markdown file
links are rewritten to `codexremote://resource/<opaque-id>` and include resource metadata;
download them with `GET /v1/tasks/:threadId/resources/:resourceId`. The Bridge resolves
both IDs back against assistant output in the current task history. User-written
links do not register downloadable resources, and network/UNC paths are rejected.

`PATCH /v1/tasks/:threadId/settings` accepts `{ "model": "...", "effort": "..." }`.
The pair is validated against the live model list and affects the next turn.

### Create a task

`POST /v1/tasks` accepts:

```json
{
  "mode": "project",
  "cwd": "C:\\known-project",
  "prompt": "Implement the requested change",
  "model": "model-id",
  "reasoningEffort": "high",
  "idempotencyKey": "stable-operation-id"
}
```

`mode` may be `project` (the default) or `quick`. Project mode accepts any existing
computer directory in `cwd`; when it is not yet listed as a Codex project, the app-server
creates the durable thread without a project ID. Quick mode omits `cwd` and creates a
durable conversation without a workspace. The model and effort must occur in the live
model list. The helper materializes and rolls back a bootstrap
turn, leaving a durable zero-turn task, then exits. It never receives the user's prompt.
After Codex Desktop becomes owner, the Bridge applies model settings and sends the real
prompt through `codex-ipc`.

A complete result is returned with status 201:

```json
{ "threadId": "...", "promptAccepted": true, "stage": "complete" }
```

If Desktop owner handoff, settings, or prompt submission fails after materialization,
status 202 returns the durable task as a partial result:

```json
{
  "threadId": "...",
  "promptAccepted": false,
  "stage": "owner",
  "error": "task-owner-handoff-timeout:..."
}
```

The stages are `owner`, `settings`, and `prompt`. Reusing the same device-scoped
idempotency key replays either complete or partial results and never creates another task.

`GET /v1/tasks/:threadId/diff` returns the complete, untruncated unified diff separately
from the compact timeline:

```json
{
  "diff": {
    "threadId": "...",
    "revision": 1,
    "turns": [
      { "turnId": "...", "status": "completed", "unifiedDiff": "..." }
    ],
    "files": [
      {
        "turnId": "...",
        "itemId": "...",
        "path": "src/app.ts",
        "kind": "update",
        "unifiedDiff": "..."
      }
    ]
  }
}
```

## Messages and queue

`POST /v1/tasks/:threadId/messages` accepts:

```json
{
  "text": "Continue",
  "delivery": "auto",
  "expectedTurnId": null,
  "expectedQueueHash": null,
  "idempotencyKey": "stable-operation-id",
  "attachmentIds": []
}
```

- `auto` keeps legacy behavior: start while idle, steer while active.
- `start` fails if a turn is active.
- `steer` requires the matching `expectedTurnId`.
- `queue` requires the latest `expectedQueueHash`; queued attachments are not supported.
- Text may be empty only when at least one attachment is present.
- Reusing an idempotency key in the same device/task scope returns the original result.

The response includes `delivery` and either `clientUserMessageId` or `queuedMessageId`.
A queue response also includes the predicted replacement `queueHash`.

`GET /v1/tasks/:threadId/queue` returns the authoritative mirrored queue:

```json
{
  "queue": {
    "threadId": "...",
    "hash": "sha256",
    "messages": [
      { "id": "...", "text": "...", "createdAt": 0 }
    ]
  }
}
```

Cancel with
`DELETE /v1/tasks/:threadId/queue/:messageId?expectedQueueHash=<hash>&idempotencyKey=<id>`.
The owner broadcasts the authoritative replacement queue after either mutation.

## Attachments

Upload raw bytes with a signed URL:

```text
POST /v1/attachments?name=<percent-encoded-name>&mimeType=<percent-encoded-mime>&idempotencyKey=<id>
Content-Type: <same MIME as signed query>
```

The response is `{ "attachment": { "attachmentId", "name", "mimeType", "size",
"kind", "expiresAt" } }`. Delete it with `DELETE /v1/attachments/:attachmentId`.

Supported images are PNG, JPEG, WebP, and GIF. Generic files are PDF and a strict
UTF-8 text/source whitelist. Executables, path components, Windows reserved names,
MIME/extension mismatches, invalid magic bytes, NUL-containing text, and files over
10 MiB are rejected. Attachments are device-owned and expire after one hour.

Images are delivered as Desktop `localImage` input. Generic files are delivered as the
Desktop-supported local `mention` input plus an `application` additional-context entry.

`GET /v1/tasks/:threadId/workspace-files?query=<text>` lists up to 150 files under the
task `cwd`, skipping dependency/build directories and symbolic links. Directory rows
use `isDirectory: true`; file rows include `attachable`, so unsupported or oversized
files remain visible in the directory tree but cannot be imported. `POST /v1/tasks/:threadId/workspace-attachments` accepts
`{ "relativePath": "docs/guide.md", "idempotencyKey": "..." }`, verifies the resolved
path remains inside that workspace, and copies it into the same device-owned attachment
store used by phone uploads.

## Safety behavior

Desktop package and bundled CLI versions are reported as `verified` only when both match
the observed adapter. A mismatch enters `best-effort` mode: known IPC operations remain
available, parseable stream payloads continue to sync, and a rejected operation reports
its own protocol error without globally disabling writes. The helper is used only to
materialize a rolled-back zero-turn task; it is closed before the user's prompt is sent
and never substitutes for the Desktop owner.
