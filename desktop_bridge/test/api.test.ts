import { createHash, createHmac } from "node:crypto";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

import { afterEach, describe, expect, it, vi } from "vitest";
import WebSocket from "ws";

import { AttachmentStore } from "../src/attachments/store.js";
import {
  BridgeController,
  type CodexControlPort,
  type OwnerHandoffOptions,
  type TaskCatalogPort,
  type TaskCreatorPort,
} from "../src/bridge/controller.js";
import { BridgeStore } from "../src/domain/store.js";
import { createBridgeApp } from "../src/http/app.js";
import {
  MemoryDeviceRegistry,
  PairingService,
} from "../src/security/device-registry.js";
import type { IpcFrame } from "../src/ipc/types.js";
import type { TurnMessageOptions } from "../src/ipc/adapter.js";

const apps: Array<ReturnType<typeof createBridgeApp>> = [];
const temporaryRoots: string[] = [];

describe("Bridge HTTP API", () => {
  afterEach(async () => {
    await Promise.all(apps.splice(0).map((app) => app.close()));
    await Promise.all(
      temporaryRoots.splice(0).map((root) =>
        rm(root, { recursive: true, force: true }),
      ),
    );
  });

  it("exposes management details only to the local computer", async () => {
    const shutdown = vi.fn();
    const { app, registry } = setup(
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      { requestShutdown: shutdown },
    );
    await registry.issue("device-local", "Pixel", "android");

    const local = await app.inject({ method: "GET", url: "/v1/local/status" });
    expect(local.statusCode).toBe(200);
    expect(local.json()).toMatchObject({
      bridge: { host: "0.0.0.0", port: 8766, buildId: "test-build" },
      ipc: "connected",
      pairing: { code: "654321" },
      devices: [{ name: "Pixel", kind: "android" }],
    });

    const remote = await app.inject({
      method: "GET",
      url: "/v1/local/status",
      remoteAddress: "100.100.10.20",
    });
    expect(remote.statusCode).toBe(403);

    const rotated = await app.inject({
      method: "POST",
      url: "/v1/local/pairing/rotate",
    });
    expect(rotated.statusCode).toBe(200);
    expect(rotated.json<{ pairing: { code: string } }>().pairing.code).not.toBe("654321");
    expect(registry.list()).toHaveLength(1);

    const removed = await app.inject({
      method: "DELETE",
      url: "/v1/local/devices/device-local",
    });
    expect(removed.statusCode).toBe(200);
    expect(registry.list()).toHaveLength(0);

    const repeatedRemoval = await app.inject({
      method: "DELETE",
      url: "/v1/local/devices/device-local",
    });
    expect(repeatedRemoval.statusCode).toBe(404);

    const stopped = await app.inject({ method: "POST", url: "/v1/local/shutdown" });
    expect(stopped.statusCode).toBe(202);
    await new Promise((resolve) => setImmediate(resolve));
    expect(shutdown).toHaveBeenCalledOnce();
  });

  it("pairs a device and requires a fresh HMAC signature", async () => {
    const { app } = setup();
    const paired = await app.inject({
      method: "POST",
      url: "/v1/pair",
      payload: { code: "654321", name: "Pixel", kind: "android" },
    });
    expect(paired.statusCode).toBe(201);
    const credential = paired.json<{ deviceId: string; token: string }>();

    const unauthorized = await app.inject({ method: "GET", url: "/v1/tasks" });
    expect(unauthorized.statusCode).toBe(401);

    const headers = signedHeaders(credential.token, "GET", "/v1/tasks", "", "req-1");
    const tasks = await app.inject({ method: "GET", url: "/v1/tasks", headers });
    expect(tasks.statusCode).toBe(200);
    expect(tasks.json()).toEqual({ tasks: [] });

    const replay = await app.inject({ method: "GET", url: "/v1/tasks", headers });
    expect(replay.statusCode).toBe(409);
  });

  it("pages task history for new clients while preserving the legacy response size", async () => {
    const { app, registry, store } = setup();
    const token = (await registry.issue("device-history", "Pixel", "android")).token;
    store.applyStreamChange("thread-history", {
      type: "snapshot",
      revision: 1,
      conversationState: {
        title: "History",
        turns: [{
          id: "turn-history",
          status: "completed",
          items: [
            { id: "user-history", type: "userMessage", content: "latest request" },
            ...Array.from({ length: 78 }, (_, index) => ({
              id: `reasoning-${index}`,
              type: "reasoning",
              summary: [`step ${index}`],
            })),
            { id: "final-history", type: "agentMessage", text: "latest result" },
          ],
        }],
        requests: [],
      },
    });

    const pagePath = "/v1/tasks/thread-history?historyLimit=10";
    const pageResponse = await app.inject({
      method: "GET",
      url: pagePath,
      headers: signedHeaders(token, "GET", pagePath, "", "history-page"),
    });
    const page = pageResponse.json<{ task: {
      items: Array<{ kind: string }>;
      hasMoreHistory: boolean;
      historyCursor: string;
    } }>().task;

    expect(pageResponse.statusCode).toBe(200);
    expect(page.items).toHaveLength(10);
    expect(page.items[0]).toMatchObject({ kind: "user" });
    expect(page.hasMoreHistory).toBe(true);

    const legacyPath = "/v1/tasks/thread-history";
    const legacyResponse = await app.inject({
      method: "GET",
      url: legacyPath,
      headers: signedHeaders(token, "GET", legacyPath, "", "history-legacy"),
    });
    expect(legacyResponse.json<{ task: { items: unknown[] } }>().task.items).toHaveLength(80);
  });

  it("sends a signed message and rejects an unconfirmed push request", async () => {
    const { app, control, registry } = setup();
    const token = (await registry.issue("device-1", "One S", "ones")).token;
    const messageBody = JSON.stringify({ text: "继续测试" });
    const message = await app.inject({
      method: "POST",
      url: "/v1/tasks/thread-1/messages",
      headers: {
        ...signedHeaders(
          token,
          "POST",
          "/v1/tasks/thread-1/messages",
          messageBody,
          "req-2",
        ),
        "content-type": "application/json",
      },
      payload: messageBody,
    });
    expect(message.statusCode).toBe(202);
    expect(control.calls).toContainEqual(["startTurn", "thread-1", "继续测试"]);

    const pushBody = JSON.stringify({ confirmed: false });
    const push = await app.inject({
      method: "POST",
      url: "/v1/tasks/thread-1/request-push",
      headers: {
        ...signedHeaders(
          token,
          "POST",
          "/v1/tasks/thread-1/request-push",
          pushBody,
          "req-3",
        ),
        "content-type": "application/json",
      },
      payload: pushBody,
    });
    expect(push.statusCode).toBe(400);
  });

  it("deduplicates message retries with a stable idempotency key", async () => {
    const { app, control, registry } = setup();
    const token = (await registry.issue("device-idempotent", "Pixel", "android")).token;
    const path = "/v1/tasks/thread-1/messages";
    const body = JSON.stringify({
      text: "只发送一次",
      delivery: "start",
      idempotencyKey: "stable-message-1",
    });

    const first = await app.inject({
      method: "POST",
      url: path,
      headers: {
        ...signedHeaders(token, "POST", path, body, "idempotency-request-1"),
        "content-type": "application/json",
      },
      payload: body,
    });
    const second = await app.inject({
      method: "POST",
      url: path,
      headers: {
        ...signedHeaders(token, "POST", path, body, "idempotency-request-2"),
        "content-type": "application/json",
      },
      payload: body,
    });

    expect(first.statusCode).toBe(202);
    expect(second.statusCode).toBe(202);
    expect(second.json()).toMatchObject({ ok: true, replayed: true, delivery: "start" });
    expect(control.calls.filter(([name]) => name === "startTurn")).toHaveLength(1);
  });

  it("activates a historical task once for signed idempotent requests", async () => {
    const activated: string[] = [];
    const catalog: TaskCatalogPort = {
      async listThreads() { return []; },
      async readThread(threadId) { return { id: threadId, status: "idle" }; },
    };
    const { app, registry } = setup(
      undefined,
      undefined,
      catalog,
      undefined,
      {
        activateThread: async (threadId) => { activated.push(threadId); },
      },
    );
    const token = (await registry.issue("device-activate", "Pixel", "android")).token;
    const capabilityPath = "/v1/capabilities";
    const capabilities = await app.inject({
      method: "GET",
      url: capabilityPath,
      headers: signedHeaders(token, "GET", capabilityPath, "", "activate-capability"),
    });
    expect(capabilities.json()).toMatchObject({ capabilities: { taskActivation: true } });

    const path = "/v1/tasks/01a0705b-c5c1-7d00-95bc-efd02a96789b/activate";
    const body = JSON.stringify({ idempotencyKey: "activate-history-1" });
    const first = await app.inject({
      method: "POST",
      url: path,
      headers: {
        ...signedHeaders(token, "POST", path, body, "activate-request-1"),
        "content-type": "application/json",
      },
      payload: body,
    });
    const replay = await app.inject({
      method: "POST",
      url: path,
      headers: {
        ...signedHeaders(token, "POST", path, body, "activate-request-2"),
        "content-type": "application/json",
      },
      payload: body,
    });

    expect(first.statusCode).toBe(200);
    expect(first.json()).toMatchObject({
      ok: true,
      ownerAvailable: true,
      alreadyOpen: false,
    });
    expect(replay.statusCode).toBe(200);
    expect(replay.json()).toMatchObject({ ok: true, replayed: true });
    expect(activated).toEqual(["01a0705b-c5c1-7d00-95bc-efd02a96789b"]);
  });

  it("uploads a signed attachment and sends its managed local path", async () => {
    const root = await mkdtemp(join(tmpdir(), "codex-remote-api-"));
    temporaryRoots.push(root);
    const attachments = new AttachmentStore(root, Date.now, () => "attachment-api-1");
    const { app, control, registry } = setup(undefined, attachments);
    const token = (await registry.issue("device-file", "Pixel", "android")).token;
    const image = png();
    const uploadPath = "/v1/attachments?name=screen.png&mimeType=image%2Fpng&idempotencyKey=upload-1";
    const upload = await app.inject({
      method: "POST",
      url: uploadPath,
      headers: {
        ...signedHeaders(token, "POST", uploadPath, image, "attachment-upload-1"),
        "content-type": "image/png",
      },
      payload: image,
    });

    expect(upload.statusCode).toBe(201);
    expect(upload.json()).toMatchObject({
      attachment: {
        attachmentId: "attachment-api-1",
        name: "screen.png",
        kind: "image",
      },
    });

    const sendPath = "/v1/tasks/thread-1/messages";
    const sendBody = JSON.stringify({
      text: "检查截图",
      delivery: "start",
      idempotencyKey: "attachment-message-1",
      attachmentIds: ["attachment-api-1"],
    });
    const sent = await app.inject({
      method: "POST",
      url: sendPath,
      headers: {
        ...signedHeaders(token, "POST", sendPath, sendBody, "attachment-send-1"),
        "content-type": "application/json",
      },
      payload: sendBody,
    });

    expect(sent.statusCode).toBe(202);
    expect(control.lastStartOptions?.attachments).toMatchObject([{
      attachmentId: "attachment-api-1",
      label: "screen.png",
      kind: "image",
      path: join(root, "attachment-api-1", "screen.png"),
    }]);
  });

  it("downloads only files explicitly linked by the task", async () => {
    const root = await mkdtemp(join(tmpdir(), "codex-remote-linked-file-"));
    temporaryRoots.push(root);
    const reportPath = join(root, "report.txt");
    await writeFile(reportPath, "verified report\n");
    const { app, registry, store } = setup();
    const token = (await registry.issue("device-download", "Pixel", "android")).token;
    store.applyStreamChange("thread-resource", {
      type: "snapshot",
      revision: 1,
      conversationState: {
        turns: [{
          id: "turn-1",
          status: "completed",
          items: [{
            id: "message-1",
            type: "agentMessage",
            text: `打开 [报告](<${pathToFileURL(reportPath).href}>)`,
          }],
        }],
        requests: [],
      },
    });

    const detailPath = "/v1/tasks/thread-resource";
    const detailResponse = await app.inject({
      method: "GET",
      url: detailPath,
      headers: signedHeaders(token, "GET", detailPath, "", "resource-detail"),
    });
    const resourceId = detailResponse.json<{ task: { items: Array<{
      resources: Array<{ resourceId: string }>;
    }> } }>().task.items[0]!.resources[0]!.resourceId;
    const resourcePath = `/v1/tasks/thread-resource/resources/${resourceId}`;
    const resourceResponse = await app.inject({
      method: "GET",
      url: resourcePath,
      headers: signedHeaders(token, "GET", resourcePath, "", "resource-download"),
    });

    expect(resourceResponse.statusCode).toBe(200);
    expect(resourceResponse.body).toBe("verified report\n");
    expect(resourceResponse.headers["content-type"]).toContain("text/plain");
  });

  it("downloads DOCX files emitted as Codex file citations", async () => {
    const root = await mkdtemp(join(tmpdir(), "codex-remote-docx-citation-"));
    temporaryRoots.push(root);
    const documentPath = join(root, "培训清单.docx");
    await writeFile(documentPath, "docx fixture");
    const { app, registry, store } = setup();
    const token = (await registry.issue("device-docx", "Pixel", "android")).token;
    store.applyStreamChange("thread-docx", {
      type: "snapshot",
      revision: 1,
      conversationState: {
        turns: [{
          id: "turn-docx",
          status: "completed",
          items: [{
            id: "message-docx",
            type: "agentMessage",
            text: `:codex-file-citation{path="${documentPath}" purpose="output"}`,
          }],
        }],
        requests: [],
      },
    });

    const detailPath = "/v1/tasks/thread-docx";
    const detailResponse = await app.inject({
      method: "GET",
      url: detailPath,
      headers: signedHeaders(token, "GET", detailPath, "", "docx-detail"),
    });
    const item = detailResponse.json<{ task: { items: Array<{
      text: string;
      resources: Array<{ resourceId: string; mimeType: string }>;
    }> } }>().task.items[0]!;
    const resource = item.resources[0]!;
    expect(item.text).toContain(`codexremote://resource/${resource.resourceId}`);
    expect(resource.mimeType).toBe(
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    );

    const resourcePath = `/v1/tasks/thread-docx/resources/${resource.resourceId}`;
    const resourceResponse = await app.inject({
      method: "GET",
      url: resourcePath,
      headers: signedHeaders(token, "GET", resourcePath, "", "docx-download"),
    });

    expect(resourceResponse.statusCode).toBe(200);
    expect(resourceResponse.body).toBe("docx fixture");
    expect(resourceResponse.headers["content-type"]).toContain(
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    );
  });

  it("lists and imports supported files from the current task workspace", async () => {
    const workspaceRoot = await mkdtemp(join(tmpdir(), "codex-remote-workspace-api-"));
    const attachmentRoot = await mkdtemp(join(tmpdir(), "codex-remote-workspace-copy-"));
    temporaryRoots.push(workspaceRoot, attachmentRoot);
    await writeFile(join(workspaceRoot, "README.md"), "# Workspace\n");
    const attachments = new AttachmentStore(
      attachmentRoot,
      Date.now,
      () => "workspace-attachment-1",
    );
    const { app, registry, store } = setup(undefined, attachments);
    const token = (await registry.issue("device-workspace", "Pixel", "android")).token;
    store.applyStreamChange("thread-workspace", {
      type: "snapshot",
      revision: 1,
      conversationState: { cwd: workspaceRoot, turns: [], requests: [] },
    });

    const listPath = "/v1/tasks/thread-workspace/workspace-files";
    const listed = await app.inject({
      method: "GET",
      url: listPath,
      headers: signedHeaders(token, "GET", listPath, "", "workspace-list"),
    });
    expect(listed.statusCode).toBe(200);
    expect(listed.json()).toMatchObject({ files: [{ relativePath: "README.md" }] });

    const importPath = "/v1/tasks/thread-workspace/workspace-attachments";
    const body = JSON.stringify({ relativePath: "README.md", idempotencyKey: "workspace-1" });
    const imported = await app.inject({
      method: "POST",
      url: importPath,
      headers: {
        ...signedHeaders(token, "POST", importPath, body, "workspace-import"),
        "content-type": "application/json",
      },
      payload: body,
    });
    expect(imported.statusCode).toBe(201);
    expect(imported.json()).toMatchObject({
      attachment: {
        attachmentId: "workspace-attachment-1",
        name: "README.md",
        mimeType: "text/markdown",
      },
    });
  });

  it("lists dynamic models and validates a thread settings update", async () => {
    const catalog: TaskCatalogPort = {
      async listThreads() { return []; },
      async listModels() {
        return [{
          id: "gpt-5.2-codex",
          displayName: "GPT-5.2 Codex",
          defaultReasoningEffort: "medium",
          supportedReasoningEfforts: [{ reasoningEffort: "medium" }],
          inputModalities: ["text", "image"],
        }];
      },
    };
    const { app, control, registry, store } = setup(undefined, undefined, catalog);
    const token = (await registry.issue("device-model", "Pixel", "android")).token;
    store.applyStreamChange("thread-1", {
      type: "snapshot",
      revision: 1,
      conversationState: {
        cwd: "C:\\repo",
        threadRuntimeStatus: { type: "idle" },
        requests: [],
      },
    });

    const modelsPath = "/v1/models";
    const models = await app.inject({
      method: "GET",
      url: modelsPath,
      headers: signedHeaders(token, "GET", modelsPath, "", "models-1"),
    });
    expect(models.statusCode).toBe(200);
    expect(models.json()).toMatchObject({
      models: [{ id: "gpt-5.2-codex", inputModalities: ["text", "image"] }],
    });

    const settingsPath = "/v1/tasks/thread-1/settings";
    const settingsBody = JSON.stringify({ model: "gpt-5.2-codex", effort: "medium" });
    const settings = await app.inject({
      method: "PATCH",
      url: settingsPath,
      headers: {
        ...signedHeaders(token, "PATCH", settingsPath, settingsBody, "settings-1"),
        "content-type": "application/json",
      },
      payload: settingsBody,
    });
    expect(settings.statusCode).toBe(202);
    expect(control.calls).toContainEqual([
      "settings",
      "thread-1",
      { model: "gpt-5.2-codex", effort: "medium" },
    ]);
  });

  it("creates a Desktop-owned task once for repeated idempotent requests", async () => {
    const catalog: TaskCatalogPort = {
      async listThreads() { return []; },
      async listModels() {
        return [{
          id: "gpt-5.2-codex",
          supportedReasoningEfforts: [{ reasoningEffort: "high" }],
        }];
      },
    };
    const materializeInputs: Array<Record<string, unknown>> = [];
    const creator: TaskCreatorPort = {
      async materialize(input) {
        materializeInputs.push(input);
        return {
          threadId: "01a0705b-c5c1-7d00-95bc-efd02a96789b",
          projectId: "project-1",
          permissionProfile: ":workspace",
        };
      },
    };
    const { app, control, registry } = setup(
      undefined,
      undefined,
      catalog,
      creator,
      { timeoutMs: 1, pollIntervalMs: 1, sleep: async () => undefined },
    );
    const token = (await registry.issue("device-create", "Pixel", "android")).token;
    const capabilitiesPath = "/v1/capabilities";
    const capabilities = await app.inject({
      method: "GET",
      url: capabilitiesPath,
      headers: signedHeaders(
        token,
        "GET",
        capabilitiesPath,
        "",
        "task-capabilities-1",
      ),
    });
    expect(capabilities.json()).toMatchObject({
      capabilities: { taskCreation: true },
    });

    const path = "/v1/tasks";
    const body = JSON.stringify({
      cwd: "C:\\repo",
      prompt: "实现手机会话界面",
      model: "gpt-5.2-codex",
      reasoningEffort: "high",
      idempotencyKey: "create-task-1",
    });
    const first = await app.inject({
      method: "POST",
      url: path,
      headers: {
        ...signedHeaders(token, "POST", path, body, "task-create-request-1"),
        "content-type": "application/json",
      },
      payload: body,
    });
    const replay = await app.inject({
      method: "POST",
      url: path,
      headers: {
        ...signedHeaders(token, "POST", path, body, "task-create-request-2"),
        "content-type": "application/json",
      },
      payload: body,
    });

    expect(first.statusCode).toBe(201);
    expect(first.json()).toEqual({
      threadId: "01a0705b-c5c1-7d00-95bc-efd02a96789b",
      promptAccepted: true,
      stage: "complete",
    });
    expect(replay.statusCode).toBe(201);
    expect(replay.json()).toMatchObject({
      threadId: "01a0705b-c5c1-7d00-95bc-efd02a96789b",
      promptAccepted: true,
      replayed: true,
    });
    expect(materializeInputs).toEqual([{
      cwd: "C:\\repo",
      model: "gpt-5.2-codex",
      effort: "high",
    }]);
    expect(JSON.stringify(materializeInputs)).not.toContain("实现手机会话界面");
    expect(control.calls.filter(([name]) => name === "startTurn")).toEqual([[
      "startTurn",
      "01a0705b-c5c1-7d00-95bc-efd02a96789b",
      "实现手机会话界面",
    ]]);
  });

  it("replays a partial created task instead of creating another one", async () => {
    let materialized = 0;
    const creator: TaskCreatorPort = {
      async materialize() {
        materialized += 1;
        return {
          threadId: "01a0705b-c5c1-7d00-95bc-efd02a96789b",
          projectId: "project-1",
          permissionProfile: ":workspace",
        };
      },
    };
    const { app, control, registry } = setup(
      undefined,
      undefined,
      {
        async listThreads() { return []; },
        async listModels() {
          return [{
            id: "gpt-5.2-codex",
            supportedReasoningEfforts: [{ reasoningEffort: "high" }],
          }];
        },
      },
      creator,
      { timeoutMs: 1, pollIntervalMs: 1, sleep: async () => undefined },
    );
    control.failStart = true;
    const token = (await registry.issue("device-partial", "Pixel", "android")).token;
    const path = "/v1/tasks";
    const body = JSON.stringify({
      cwd: "C:\\repo",
      prompt: "真实任务",
      model: "gpt-5.2-codex",
      reasoningEffort: "high",
      idempotencyKey: "partial-task-1",
    });

    const first = await app.inject({
      method: "POST",
      url: path,
      headers: {
        ...signedHeaders(token, "POST", path, body, "partial-request-1"),
        "content-type": "application/json",
      },
      payload: body,
    });
    const replay = await app.inject({
      method: "POST",
      url: path,
      headers: {
        ...signedHeaders(token, "POST", path, body, "partial-request-2"),
        "content-type": "application/json",
      },
      payload: body,
    });

    expect(first.statusCode).toBe(202);
    expect(first.json()).toMatchObject({
      threadId: "01a0705b-c5c1-7d00-95bc-efd02a96789b",
      promptAccepted: false,
      stage: "prompt",
      error: "desktop-start-rejected",
    });
    expect(replay.statusCode).toBe(202);
    expect(replay.json()).toMatchObject({
      promptAccepted: false,
      stage: "prompt",
      replayed: true,
    });
    expect(materialized).toBe(1);
    expect(control.calls.filter(([name]) => name === "startTurn")).toHaveLength(1);
  });

  it("exposes hash-checked queue mutation and complete diff endpoints", async () => {
    const { app, control, registry, controller, store } = setup();
    const token = (await registry.issue("device-queue", "Pixel", "android")).token;
    store.applyStreamChange("thread-1", {
      type: "snapshot",
      revision: 2,
      conversationState: {
        cwd: "C:\\repo",
        threadRuntimeStatus: { type: "active", turnId: "turn-1" },
        turns: [{
          id: "turn-1",
          status: "inProgress",
          diff: "diff --git a/a b/a\n@@ -1 +1 @@\n-old\n+new\n",
          items: [],
        }],
        requests: [],
      },
    });
    store.applyQueueSnapshot("thread-1", [{ id: "queued-1", text: "已有", cwd: "C:\\repo" }]);
    const initial = controller.getQueue("thread-1");

    const queuePath = "/v1/tasks/thread-1/queue";
    const queue = await app.inject({
      method: "GET",
      url: queuePath,
      headers: signedHeaders(token, "GET", queuePath, "", "queue-get-1"),
    });
    expect(queue.statusCode).toBe(200);
    expect(queue.json()).toMatchObject({ queue: { hash: initial.hash, messages: [{ id: "queued-1" }] } });

    const messagePath = "/v1/tasks/thread-1/messages";
    const messageBody = JSON.stringify({
      text: "下一条",
      delivery: "queue",
      expectedQueueHash: initial.hash,
      idempotencyKey: "queue-add-1",
    });
    const queued = await app.inject({
      method: "POST",
      url: messagePath,
      headers: {
        ...signedHeaders(token, "POST", messagePath, messageBody, "queue-add-request-1"),
        "content-type": "application/json",
      },
      payload: messageBody,
    });
    expect(queued.statusCode).toBe(202);
    expect(queued.json()).toMatchObject({ ok: true, delivery: "queue" });

    const deletePath = `/v1/tasks/thread-1/queue/queued-1?expectedQueueHash=${initial.hash}&idempotencyKey=queue-delete-1`;
    const deleted = await app.inject({
      method: "DELETE",
      url: deletePath,
      headers: signedHeaders(token, "DELETE", deletePath, "", "queue-delete-request-1"),
    });
    expect(deleted.statusCode).toBe(200);
    expect(control.calls.filter(([name]) => name === "queue")).toHaveLength(2);

    const diffPath = "/v1/tasks/thread-1/diff";
    const diff = await app.inject({
      method: "GET",
      url: diffPath,
      headers: signedHeaders(token, "GET", diffPath, "", "diff-get-1"),
    });
    expect(diff.statusCode).toBe(200);
    expect(diff.json()).toMatchObject({
      diff: {
        threadId: "thread-1",
        turns: [{ turnId: "turn-1", unifiedDiff: expect.stringContaining("+new") }],
      },
    });
  });

  it("uploads voice, allows an edited send, and expires the voice session", async () => {
    const transcriber = {
      status: () => ({ available: true }),
      transcribe: async () => "继续检查测试",
    };
    const { app, control, registry } = setup(transcriber);
    const token = (await registry.issue("device-2", "One S", "ones")).token;
    const wav = wav8(22_050, 2_205);
    const voicePath = "/v1/voice";
    const upload = await app.inject({
      method: "POST",
      url: voicePath,
      headers: {
        ...signedHeaders(token, "POST", voicePath, wav, "voice-upload"),
        "content-type": "audio/wav",
      },
      payload: wav,
    });
    expect(upload.statusCode).toBe(201);
    const voice = upload.json<{ voiceId: string; transcript: string }>();
    expect(voice.transcript).toBe("继续检查测试");

    const sendPath = `/v1/voice/${voice.voiceId}/send`;
    const sendBody = JSON.stringify({ threadId: "thread-1", text: "修改后的文字" });
    const send = await app.inject({
      method: "POST",
      url: sendPath,
      headers: {
        ...signedHeaders(token, "POST", sendPath, sendBody, "voice-send"),
        "content-type": "application/json",
      },
      payload: sendBody,
    });
    expect(send.statusCode).toBe(202);
    expect(control.calls).toContainEqual(["startTurn", "thread-1", "修改后的文字"]);

    const repeatBody = JSON.stringify({ threadId: "thread-1" });
    const repeat = await app.inject({
      method: "POST",
      url: sendPath,
      headers: {
        ...signedHeaders(token, "POST", sendPath, repeatBody, "voice-repeat"),
        "content-type": "application/json",
      },
      payload: repeatBody,
    });
    expect(repeat.statusCode).toBe(404);
  });

  it("cancels a transcribed voice session", async () => {
    const transcriber = {
      status: () => ({ available: true }),
      transcribe: async () => "不要发送",
    };
    const { app, registry } = setup(transcriber);
    const token = (await registry.issue("device-3", "One S", "ones")).token;
    const wav = wav8(22_050, 2_205);
    const upload = await app.inject({
      method: "POST",
      url: "/v1/voice",
      headers: {
        ...signedHeaders(token, "POST", "/v1/voice", wav, "voice-upload-2"),
        "content-type": "audio/wav",
      },
      payload: wav,
    });
    const voiceId = upload.json<{ voiceId: string }>().voiceId;
    const path = `/v1/voice/${voiceId}`;
    const cancelled = await app.inject({
      method: "DELETE",
      url: path,
      headers: signedHeaders(token, "DELETE", path, "", "voice-cancel"),
    });
    expect(cancelled.statusCode).toBe(200);
  });

  it("accepts only the first valid response to request_user_input", async () => {
    const { app, control, registry, store } = setup();
    const token = (await registry.issue("device-4", "Pixel", "android")).token;
    store.registerApproval({
      requestId: "input-1",
      threadId: "thread-1",
      method: "item/tool/requestUserInput",
      expiresAt: Date.now() + 60_000,
      payload: {},
    });
    const path = "/v1/user-input/input-1";
    const body = JSON.stringify({
      response: { answers: { mode: { answers: ["继续"] } } },
    });
    const first = await app.inject({
      method: "POST",
      url: path,
      headers: {
        ...signedHeaders(token, "POST", path, body, "input-answer-1"),
        "content-type": "application/json",
      },
      payload: body,
    });
    expect(first.statusCode).toBe(200);
    const second = await app.inject({
      method: "POST",
      url: path,
      headers: {
        ...signedHeaders(token, "POST", path, body, "input-answer-2"),
        "content-type": "application/json",
      },
      payload: body,
    });
    expect(second.statusCode).toBe(404);
    expect(control.calls.filter(([name]) => name === "userInput")).toHaveLength(1);
  });

  it("revokes the calling device credential", async () => {
    const { app, registry } = setup();
    const credential = await registry.issue("device-5", "Pixel", "android");
    const path = "/v1/devices/self";
    const body = "{}";
    const revoked = await app.inject({
      method: "DELETE",
      url: path,
      headers: {
        ...signedHeaders(credential.token, "DELETE", path, body, "revoke-self"),
        "content-type": "application/json",
      },
      payload: body,
    });
    expect(revoked.statusCode).toBe(200);

    const after = await app.inject({
      method: "GET",
      url: "/v1/tasks",
      headers: signedHeaders(
        credential.token,
        "GET",
        "/v1/tasks",
        "",
        "after-revoke",
      ),
    });
    expect(after.statusCode).toBe(401);
  });

  it("authenticates a websocket and streams sequenced events", async () => {
    const { app, registry, store } = setup();
    const credential = await registry.issue("device-6", "Pixel", "android");
    const path = "/v1/stream";
    await app.listen({ host: "127.0.0.1", port: 0 });
    const address = app.server.address();
    if (!address || typeof address === "string") throw new Error("test-listener-missing");
    const socket = new WebSocket(`ws://127.0.0.1:${address.port}${path}`, {
      headers: signedHeaders(
        credential.token,
        "GET",
        path,
        "",
        "websocket-connect",
      ),
    });
    await new Promise<void>((resolve, reject) => {
      socket.once("open", resolve);
      socket.once("error", reject);
      socket.once("unexpected-response", (_request, response) => {
        const chunks: Buffer[] = [];
        response.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
        response.on("end", () => {
          reject(
            new Error(
              `websocket-${response.statusCode}:${Buffer.concat(chunks).toString("utf8")}`,
            ),
          );
        });
      });
    });
    const message = new Promise<string>((resolve) => {
      socket.once("message", (data) => resolve(data.toString()));
    });

    store.appendEvent("test.event", { ok: true });

    expect(JSON.parse(await message)).toMatchObject({
      sequence: 1,
      type: "test.event",
      payload: { ok: true },
    });
    socket.terminate();
  });

  it("terminates a websocket that stops answering heartbeat pings", async () => {
    const { app, registry, store } = setup(
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      { webSocketHeartbeatIntervalMs: 20 },
    );
    const originalSubscribe = store.subscribe.bind(store);
    let unsubscribeCount = 0;
    vi.spyOn(store, "subscribe").mockImplementation((listener) => {
      const unsubscribe = originalSubscribe(listener);
      return () => {
        unsubscribeCount += 1;
        unsubscribe();
      };
    });
    const credential = await registry.issue("device-heartbeat", "Pixel", "android");
    const path = "/v1/stream";
    await app.listen({ host: "127.0.0.1", port: 0 });
    const address = app.server.address();
    if (!address || typeof address === "string") throw new Error("test-listener-missing");
    const socket = new WebSocket(`ws://127.0.0.1:${address.port}${path}`, {
      autoPong: false,
      headers: signedHeaders(
        credential.token,
        "GET",
        path,
        "",
        "websocket-heartbeat",
      ),
    });
    await socketOpened(socket);

    await socketClosed(socket, 1_000);

    expect(unsubscribeCount).toBe(1);
  });

  it("terminates and unsubscribes a websocket before its send buffer exceeds the limit", async () => {
    const { app, registry, store } = setup(
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      {
        webSocketHeartbeatIntervalMs: 1_000,
        webSocketMaxBufferedBytes: 64,
      },
    );
    const originalSubscribe = store.subscribe.bind(store);
    let unsubscribeCount = 0;
    vi.spyOn(store, "subscribe").mockImplementation((listener) => {
      const unsubscribe = originalSubscribe(listener);
      return () => {
        unsubscribeCount += 1;
        unsubscribe();
      };
    });
    const credential = await registry.issue("device-backpressure", "Pixel", "android");
    const path = "/v1/stream";
    await app.listen({ host: "127.0.0.1", port: 0 });
    const address = app.server.address();
    if (!address || typeof address === "string") throw new Error("test-listener-missing");
    const socket = new WebSocket(`ws://127.0.0.1:${address.port}${path}`, {
      headers: signedHeaders(
        credential.token,
        "GET",
        path,
        "",
        "websocket-backpressure",
      ),
    });
    await socketOpened(socket);
    const closed = socketClosed(socket, 1_000);

    store.appendEvent("test.large", { text: "x".repeat(256) });

    await closed;
    expect(unsubscribeCount).toBe(1);
  });
});

function setup(transcriber?: {
  status(): { available: boolean };
  transcribe(source: Buffer): Promise<string>;
}, attachments?: AttachmentStore, catalog?: TaskCatalogPort,
taskCreator?: TaskCreatorPort, ownerHandoff?: OwnerHandoffOptions,
appOptions: TestBridgeAppOptions = {}) {
  const store = new BridgeStore();
  const control = new ApiFakeControl();
  const controller = new BridgeController(
    control,
    store,
    catalog,
    taskCreator,
    ownerHandoff,
  );
  const registry = new MemoryDeviceRegistry();
  const pairing = new PairingService(registry, "654321");
  const app = createBridgeApp({
    controller,
    store,
    registry,
    pairing,
    ipcStatus: () => "connected",
    asrStatus: () => ({ available: false, reason: "not-configured" }),
    localStatus: () => ({ host: "0.0.0.0", port: 8766, buildId: "test-build" }),
    ...(appOptions.requestShutdown
      ? { requestShutdown: appOptions.requestShutdown }
      : {}),
    ...(transcriber ? { transcriber } : {}),
    ...(attachments ? { attachments } : {}),
  }, appOptions);
  apps.push(app);
  return { app, control, registry, store, controller };
}

type TestBridgeAppOptions = {
  webSocketHeartbeatIntervalMs?: number;
  webSocketMaxBufferedBytes?: number;
  requestShutdown?: () => void;
};

function socketOpened(socket: WebSocket): Promise<void> {
  return new Promise((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
    socket.once("unexpected-response", (_request, response) => {
      response.resume();
      reject(new Error(`websocket-${response.statusCode}`));
    });
  });
}

function socketClosed(socket: WebSocket, timeoutMs: number): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("websocket-close-timeout")), timeoutMs);
    socket.once("close", () => {
      clearTimeout(timer);
      resolve();
    });
  });
}

function signedHeaders(
  token: string,
  method: string,
  path: string,
  body: string | Buffer,
  requestId: string,
): Record<string, string> {
  const timestamp = String(Math.floor(Date.now() / 1_000));
  const bodyHash = createHash("sha256").update(body).digest("hex");
  const signature = createHmac("sha256", token)
    .update([method, path, timestamp, requestId, bodyHash].join("\n"))
    .digest("hex");
  return {
    authorization: `Bearer ${token}`,
    "x-request-id": requestId,
    "x-timestamp": timestamp,
    "x-signature": signature,
  };
}

function wav8(sampleRate: number, frameCount: number): Buffer {
  const data = Buffer.alloc(frameCount, 128);
  const output = Buffer.alloc(44 + data.length);
  output.write("RIFF", 0, "ascii");
  output.writeUInt32LE(output.length - 8, 4);
  output.write("WAVE", 8, "ascii");
  output.write("fmt ", 12, "ascii");
  output.writeUInt32LE(16, 16);
  output.writeUInt16LE(1, 20);
  output.writeUInt16LE(1, 22);
  output.writeUInt32LE(sampleRate, 24);
  output.writeUInt32LE(sampleRate, 28);
  output.writeUInt16LE(1, 32);
  output.writeUInt16LE(8, 34);
  output.write("data", 36, "ascii");
  output.writeUInt32LE(data.length, 40);
  data.copy(output, 44);
  return output;
}

function png(): Buffer {
  return Buffer.from([
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    0x00, 0x00, 0x00, 0x00,
  ]);
}

class ApiFakeControl implements CodexControlPort {
  calls: Array<[string, ...unknown[]]> = [];
  lastStartOptions: TurnMessageOptions | undefined;
  failStart = false;
  writable = true;
  compatibility = {
    supported: true,
    expected: "26.901.1978.0",
    installed: "26.901.1978.0",
  };
  async discoverOwner(): Promise<string> { return "desktop-owner"; }
  async loadHistory(): Promise<IpcFrame> { return ok(); }
  async startTurn(
    threadId: string,
    text: string,
    options?: TurnMessageOptions,
  ): Promise<IpcFrame> {
    this.lastStartOptions = options;
    this.calls.push(["startTurn", threadId, text]);
    if (this.failStart) throw new Error("desktop-start-rejected");
    return ok();
  }
  async steer(threadId: string, text: string): Promise<IpcFrame> {
    this.calls.push(["steer", threadId, text]); return ok();
  }
  async updateThreadSettings(
    threadId: string,
    settings: { model: string; effort: string },
  ): Promise<IpcFrame> {
    this.calls.push(["settings", threadId, settings]); return ok();
  }
  async setQueuedFollowUps(
    threadId: string,
    messages: Array<Record<string, unknown>>,
  ): Promise<IpcFrame> {
    this.calls.push(["queue", threadId, messages]); return ok();
  }
  async interrupt(): Promise<IpcFrame> { return ok(); }
  async respondToApproval(): Promise<IpcFrame> { return ok(); }
  async respondToUserInput(
    threadId: string,
    requestId: string,
    response: Record<string, unknown>,
  ): Promise<IpcFrame> {
    this.calls.push(["userInput", threadId, requestId, response]);
    return ok();
  }
}

function ok(): IpcFrame {
  return { type: "response", requestId: "ok", resultType: "success" };
}
