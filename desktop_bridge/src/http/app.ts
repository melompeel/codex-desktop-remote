import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";

import Fastify, { type FastifyRequest } from "fastify";
import websocket from "@fastify/websocket";
import type WebSocket from "ws";

import {
  type AttachmentStore,
  MAX_ATTACHMENT_BYTES,
} from "../attachments/store.js";
import type { VoiceTranscriber } from "../asr/whisper.js";
import { VoiceSessionStore } from "../asr/voice-sessions.js";
import type {
  BridgeController,
  MessageDelivery,
  TaskListQuery,
} from "../bridge/controller.js";
import type { BridgeStore } from "../domain/store.js";
import type {
  DeviceRecord,
  DeviceRegistry,
  PairingService,
} from "../security/device-registry.js";
import { RequestAuthenticator } from "../security/request-auth.js";
import {
  listWorkspaceFiles,
  readWorkspaceAttachment,
} from "../workspace/files.js";

type AsrStatus = { available: boolean; reason?: string };
const MAX_REMOTE_IMAGE_BYTES = 10 * 1024 * 1024;
const MAX_REMOTE_RESOURCE_BYTES = 25 * 1024 * 1024;
const DEFAULT_WEBSOCKET_HEARTBEAT_INTERVAL_MS = 30_000;
const DEFAULT_WEBSOCKET_MAX_BUFFERED_BYTES = 512 * 1024;

export type BridgeAppDependencies = {
  controller: BridgeController;
  store: BridgeStore;
  registry: DeviceRegistry;
  pairing: PairingService;
  ipcStatus: () => string;
  asrStatus: () => AsrStatus;
  transcriber?: VoiceTranscriber;
  voiceSessions?: VoiceSessionStore;
  attachments?: AttachmentStore;
  localStatus?: () => Record<string, unknown>;
  requestShutdown?: () => void;
};

export type BridgeAppOptions = {
  webSocketHeartbeatIntervalMs?: number;
  webSocketMaxBufferedBytes?: number;
};

type AuthenticatedRequest = FastifyRequest & {
  rawBody?: Buffer;
  device?: DeviceRecord;
};

export function createBridgeApp(
  dependencies: BridgeAppDependencies,
  options: BridgeAppOptions = {},
) {
  const app = Fastify({ logger: false, bodyLimit: MAX_ATTACHMENT_BYTES });
  const authenticator = new RequestAuthenticator();
  const voiceSessions = dependencies.voiceSessions ?? new VoiceSessionStore();
  const webSocketHeartbeatIntervalMs = positiveIntegerOption(
    options.webSocketHeartbeatIntervalMs,
    DEFAULT_WEBSOCKET_HEARTBEAT_INTERVAL_MS,
    "invalid-websocket-heartbeat-interval",
  );
  const webSocketMaxBufferedBytes = positiveIntegerOption(
    options.webSocketMaxBufferedBytes,
    DEFAULT_WEBSOCKET_MAX_BUFFERED_BYTES,
    "invalid-websocket-buffer-limit",
  );

  app.register(websocket);
  app.addContentTypeParser(
    "application/json",
    { parseAs: "buffer" },
    (request, body, done) => {
      try {
        const buffer = body as Buffer;
        (request as AuthenticatedRequest).rawBody = buffer;
        done(null, JSON.parse(buffer.toString("utf8")));
      } catch (error) {
        done(error as Error, undefined);
      }
    },
  );
  app.addContentTypeParser(
    [
      "application/octet-stream",
      "audio/wav",
      "image/png",
      "image/jpeg",
      "image/webp",
      "image/gif",
      "application/pdf",
      "text/markdown",
      "text/csv",
      "application/xml",
      "text/xml",
      "application/yaml",
      "text/yaml",
    ],
    { parseAs: "buffer" },
    (request, body, done) => {
      const buffer = body as Buffer;
      (request as AuthenticatedRequest).rawBody = buffer;
      done(null, buffer);
    },
  );
  app.removeContentTypeParser("text/plain");
  app.addContentTypeParser(
    "text/plain",
    { parseAs: "buffer" },
    (request, body, done) => {
      const buffer = body as Buffer;
      (request as AuthenticatedRequest).rawBody = buffer;
      done(null, buffer);
    },
  );

  app.addHook("preHandler", async (request, reply) => {
    if (request.url.startsWith("/v1/local/")) {
      if (!isLoopbackAddress(request.socket.remoteAddress)) {
        await reply.code(403).send({ error: "local-access-only" });
        return reply;
      }
      return;
    }
    if (request.url === "/v1/health" || request.url === "/v1/pair") return;
    const authorization = request.headers.authorization ?? "";
    const token = authorization.startsWith("Bearer ")
      ? authorization.slice("Bearer ".length)
      : "";
    const device = token ? dependencies.registry.findByToken(token) : null;
    if (!device) {
      await reply.code(401).send({ error: "unauthorized" });
      return reply;
    }
    const timestamp = readHeader(request, "x-timestamp");
    const requestId = readHeader(request, "x-request-id");
    const signature = readHeader(request, "x-signature");
    if (!timestamp || !requestId || !signature) {
      await reply.code(401).send({ error: "missing-request-signature" });
      return reply;
    }
    try {
      authenticator.verify({
        token,
        method: request.method,
        path: request.raw.url ?? request.url,
        timestamp,
        requestId,
        signature,
        body: (request as AuthenticatedRequest).rawBody ?? Buffer.alloc(0),
      });
      (request as AuthenticatedRequest).device = device;
    } catch (error) {
      const message = errorMessage(error);
      await reply
        .code(message === "request-replayed" ? 409 : 401)
        .send({ error: message });
      return reply;
    }
  });

  app.setErrorHandler((error, _request, reply) => {
    const message = errorMessage(error);
    const explicitStatus = (error as Error & { statusCode?: number }).statusCode;
    const status = explicitStatus && explicitStatus >= 400
      ? explicitStatus
      : message.includes("not-found") ? 404
      : message.includes("too-large") ? 413
      : message.includes("unsupported-attachment-media-type") ? 415
      : message.includes("already-resolved") ||
          message.includes("expired") ||
          message.includes("conflict") ||
          message.includes("stale-turn") ||
          message.includes("in-progress") ||
          message.includes("idempotency-in-progress") ? 409
      : message.includes("unsupported-desktop-version") ||
          message.includes("owner") ||
          message.includes("unavailable") ? 503
      : 400;
    void reply.code(status).send({ error: message });
  });

  app.get("/v1/health", async () => ({
    ok: true,
    ipc: dependencies.ipcStatus(),
    compatibility: dependencies.controller.compatibility,
    asr: dependencies.asrStatus(),
  }));

  app.get("/v1/local/status", async () => ({
    bridge: dependencies.localStatus?.() ?? {},
    ipc: dependencies.ipcStatus(),
    compatibility: dependencies.controller.compatibility,
    asr: dependencies.asrStatus(),
    pairing: {
      code: dependencies.pairing.currentCode,
      expiresAt: dependencies.pairing.codeExpiresAt,
    },
    devices: dependencies.registry.list().map(({ deviceId, name, kind, createdAt }) => ({
      deviceId,
      name,
      kind,
      createdAt,
    })),
  }));

  app.post("/v1/local/pairing/rotate", async () => ({
    pairing: dependencies.pairing.rotate(),
  }));

  app.delete("/v1/local/devices/:deviceId", async (request, reply) => {
    const deviceId = routeParam(request, "deviceId");
    const existing = dependencies.registry.list().find((device) => device.deviceId === deviceId);
    if (!existing) return reply.code(404).send({ error: "device-not-found" });
    await dependencies.registry.revoke(deviceId);
    return { ok: true };
  });

  app.post("/v1/local/shutdown", async (_request, reply) => {
    if (!dependencies.requestShutdown) {
      return reply.code(503).send({ error: "shutdown-unavailable" });
    }
    setImmediate(dependencies.requestShutdown);
    return reply.code(202).send({ ok: true });
  });

  app.get("/v1/capabilities", async () => ({
    capabilities: {
      ...dependencies.controller.capabilities,
      attachments: {
        enabled: Boolean(dependencies.attachments),
        kinds: ["image", "file"],
        queued: false,
        maxBytes: MAX_ATTACHMENT_BYTES,
      },
    },
  }));

  app.get("/v1/models", async (request) => ({
    models: await dependencies.controller.listModels(
      parseRefreshQuery(asRecord(request.query)?.refresh),
    ),
  }));

  app.post("/v1/pair", async (request, reply) => {
    const body = asRecord(request.body);
    const code = readString(body?.code);
    const name = readString(body?.name);
    const kind = body?.kind;
    if (!code || !name || (kind !== "android" && kind !== "ones")) {
      return reply.code(400).send({ error: "invalid-pairing-request" });
    }
    const credential = await dependencies.pairing.pair({ code, name, kind });
    return reply.code(201).send(credential);
  });

  app.post("/v1/tasks", async (request, reply) => {
    const body = asRecord(request.body);
    const mode = body?.mode === "quick" || body?.mode === "project" ? body.mode : undefined;
    const cwd = readString(body?.cwd);
    const prompt = readString(body?.prompt);
    const model = readString(body?.model);
    const reasoningEffort = readString(body?.reasoningEffort);
    const idempotencyKey = readString(body?.idempotencyKey);
    if ((mode === "project" && !cwd) || !prompt || !model || !reasoningEffort || !idempotencyKey) {
      return reply.code(400).send({ error: "invalid-task-create-request" });
    }
    const device = authenticatedDevice(request);
    const scope = `${device.deviceId}:task-create`;
    const claim = dependencies.store.beginIdempotent(scope, idempotencyKey);
    if (claim.replayed) {
      const result = asRecord(claim.result);
      if (!result || typeof result.promptAccepted !== "boolean") {
        throw new Error("invalid-task-create-idempotency-result");
      }
      return reply
        .code(result.promptAccepted ? 201 : 202)
        .send({ ...result, replayed: true });
    }
    try {
      const result = await dependencies.controller.createTask({
        ...(cwd ? { cwd } : {}),
        ...(mode ? { mode } : {}),
        prompt,
        model,
        reasoningEffort,
      });
      dependencies.store.completeIdempotent(scope, idempotencyKey, result);
      return reply
        .code(result.promptAccepted ? 201 : 202)
        .send(result);
    } catch (error) {
      dependencies.store.releaseIdempotent(scope, idempotencyKey);
      throw error;
    }
  });

  app.get("/v1/tasks", async (request) => {
    const query = asRecord(request.query);
    const parsed = parseTaskListQuery(query);
    const page = await dependencies.controller.listTaskPage(parsed);
    const hasPagingQuery = Boolean(
      query && Object.keys(query).some((key) =>
        ["query", "searchTerm", "archived", "cursor", "limit"].includes(key),
      ),
    );
    return hasPagingQuery ? page : { tasks: page.tasks };
  });

  app.delete("/v1/devices/self", async (request) => {
    const device = (request as AuthenticatedRequest).device;
    if (!device) throw new Error("unauthorized");
    await dependencies.registry.revoke(device.deviceId);
    return { ok: true };
  });

  app.get("/v1/tasks/:threadId", async (request) => ({
    task: await dependencies.controller.getTaskDetail(
      routeParam(request, "threadId"),
      parseTaskHistoryQuery(asRecord(request.query)),
    ),
  }));

  app.get("/v1/tasks/:threadId/media/:mediaId", async (request, reply) => {
    const media = await dependencies.controller.getTaskMedia(
      routeParam(request, "threadId"),
      routeParam(request, "mediaId"),
    );
    const metadata = await stat(media.fsPath).catch(() => null);
    if (!metadata?.isFile()) throw new Error("task-media-not-found");
    if (metadata.size > MAX_REMOTE_IMAGE_BYTES) throw new Error("task-media-too-large");
    reply
      .type(media.mimeType)
      .header("Content-Length", metadata.size)
      .header("Cache-Control", "private, max-age=300")
      .header(
        "Content-Disposition",
        `inline; filename*=UTF-8''${encodeURIComponent(media.name)}`,
      );
    return reply.send(createReadStream(media.fsPath));
  });

  app.get("/v1/tasks/:threadId/resources/:resourceId", async (request, reply) => {
    const resource = await dependencies.controller.getTaskResource(
      routeParam(request, "threadId"),
      routeParam(request, "resourceId"),
    );
    const metadata = await stat(resource.fsPath).catch(() => null);
    if (!metadata?.isFile()) throw new Error("task-resource-not-found");
    if (metadata.size > MAX_REMOTE_RESOURCE_BYTES) {
      throw new Error("task-resource-too-large");
    }
    reply
      .type(resource.mimeType)
      .header("Content-Length", metadata.size)
      .header("Cache-Control", "private, max-age=300")
      .header(
        "Content-Disposition",
        `attachment; filename*=UTF-8''${encodeURIComponent(resource.name)}`,
      );
    return reply.send(createReadStream(resource.fsPath));
  });

  app.get("/v1/tasks/:threadId/workspace-files", async (request) => {
    const threadId = routeParam(request, "threadId");
    const task = await dependencies.controller.getTaskDetail(threadId);
    if (!task.cwd) throw new Error("thread-cwd-required");
    const query = readString(asRecord(request.query)?.query) ?? "";
    return { files: await listWorkspaceFiles(task.cwd, query) };
  });

  app.post("/v1/tasks/:threadId/workspace-attachments", async (request, reply) => {
    const threadId = routeParam(request, "threadId");
    const body = asRecord(request.body);
    const relativePath = readString(body?.relativePath);
    const idempotencyKey = readString(body?.idempotencyKey) ?? requiredRequestId(request);
    if (!relativePath) return reply.code(400).send({ error: "workspace-file-path-required" });
    const device = authenticatedDevice(request);
    const scope = `${device.deviceId}:workspace-attachment:${threadId}`;
    const claim = dependencies.store.beginIdempotent(scope, idempotencyKey);
    if (claim.replayed) {
      return reply.code(201).send({ attachment: asRecord(claim.result), replayed: true });
    }
    try {
      const task = await dependencies.controller.getTaskDetail(threadId);
      if (!task.cwd) throw new Error("thread-cwd-required");
      const file = await readWorkspaceAttachment(task.cwd, relativePath);
      const attachment = await requireAttachmentStore(dependencies).save(
        device.deviceId,
        file.name,
        file.mimeType,
        file.body,
      );
      dependencies.store.completeIdempotent(scope, idempotencyKey, attachment);
      return reply.code(201).send({ attachment });
    } catch (error) {
      dependencies.store.releaseIdempotent(scope, idempotencyKey);
      throw error;
    }
  });

  app.get("/v1/tasks/:threadId/diff", async (request) => ({
    diff: await dependencies.controller.getTaskDiff(routeParam(request, "threadId")),
  }));

  app.get("/v1/tasks/:threadId/queue", async (request) => ({
    queue: dependencies.controller.getQueue(routeParam(request, "threadId")),
  }));

  app.patch("/v1/tasks/:threadId/settings", async (request, reply) => {
    const threadId = routeParam(request, "threadId");
    const body = asRecord(request.body);
    const model = readString(body?.model);
    const effort = readString(body?.effort);
    if (!model || !effort) {
      return reply.code(400).send({ error: "invalid-thread-settings" });
    }
    await dependencies.controller.updateThreadSettings(threadId, { model, effort });
    return reply.code(202).send({ ok: true, model, effort });
  });

  app.get("/v1/approvals", async () => ({
    approvals: dependencies.store.listApprovals(),
  }));

  app.get("/v1/events", async (request) => {
    const query = asRecord(request.query);
    const cursor = Number(query?.cursor ?? 0);
    return {
      events: dependencies.store.eventsAfter(Number.isFinite(cursor) ? cursor : 0),
    };
  });

  app.register(async (streamScope) => {
    streamScope.get("/v1/stream", { websocket: true }, (socket) => {
      manageEventStream(
        socket,
        dependencies.store,
        webSocketHeartbeatIntervalMs,
        webSocketMaxBufferedBytes,
      );
    });
  });

  app.post("/v1/tasks/:threadId/follow", async (request, reply) => {
    const threadId = routeParam(request, "threadId");
    await dependencies.controller.follow(threadId);
    return reply.code(202).send({ ok: true });
  });

  app.post("/v1/tasks/:threadId/activate", async (request, reply) => {
    const threadId = routeParam(request, "threadId");
    const body = asRecord(request.body);
    const device = authenticatedDevice(request);
    const idempotencyKey =
      readString(body?.idempotencyKey) ?? requiredRequestId(request);
    const scope = `${device.deviceId}:activate:${threadId}`;
    const claim = dependencies.store.beginIdempotent(scope, idempotencyKey);
    if (claim.replayed) {
      return reply.code(200).send({
        ok: true,
        replayed: true,
        ...(asRecord(claim.result) ?? {}),
      });
    }
    try {
      const result = await dependencies.controller.activateTask(threadId);
      dependencies.store.completeIdempotent(scope, idempotencyKey, result);
      return reply.code(200).send({ ok: true, ...result });
    } catch (error) {
      dependencies.store.releaseIdempotent(scope, idempotencyKey);
      throw error;
    }
  });

  app.post("/v1/tasks/:threadId/messages", async (request, reply) => {
    const threadId = routeParam(request, "threadId");
    const body = asRecord(request.body);
    const text = typeof body?.text === "string" ? body.text : "";
    const delivery = parseDelivery(body?.delivery);
    const attachmentIds = parseStringArray(body?.attachmentIds, "invalid-attachment-ids");
    const device = authenticatedDevice(request);
    const idempotencyKey =
      readString(body?.idempotencyKey) ?? requiredRequestId(request);
    const scope = `${device.deviceId}:message:${threadId}`;
    const claim = dependencies.store.beginIdempotent(scope, idempotencyKey);
    if (claim.replayed) {
      return reply.code(202).send({
        ok: true,
        replayed: true,
        ...(asRecord(claim.result) ?? {}),
      });
    }
    try {
      const attachments = attachmentIds.length > 0
        ? await requireAttachmentStore(dependencies).resolve(device.deviceId, attachmentIds)
        : [];
      const result = await dependencies.controller.sendMessage(threadId, text, {
        delivery,
        ...(readString(body?.expectedTurnId)
          ? { expectedTurnId: readString(body?.expectedTurnId)! }
          : {}),
        ...(readString(body?.expectedQueueHash)
          ? { expectedQueueHash: readString(body?.expectedQueueHash)! }
          : {}),
        attachments,
      });
      dependencies.store.completeIdempotent(scope, idempotencyKey, result);
      return reply.code(202).send({ ok: true, ...result });
    } catch (error) {
      dependencies.store.releaseIdempotent(scope, idempotencyKey);
      throw error;
    }
  });

  app.delete("/v1/tasks/:threadId/queue/:messageId", async (request, reply) => {
    const threadId = routeParam(request, "threadId");
    const messageId = routeParam(request, "messageId");
    const query = asRecord(request.query);
    const expectedQueueHash = readString(query?.expectedQueueHash);
    if (!expectedQueueHash) {
      return reply.code(400).send({ error: "expected-queue-hash-required" });
    }
    const device = authenticatedDevice(request);
    const idempotencyKey = readString(query?.idempotencyKey) ?? requiredRequestId(request);
    const scope = `${device.deviceId}:queue-delete:${threadId}:${messageId}`;
    const claim = dependencies.store.beginIdempotent(scope, idempotencyKey);
    if (claim.replayed) {
      return reply.code(200).send({
        ok: true,
        replayed: true,
        ...(asRecord(claim.result) ?? {}),
      });
    }
    try {
      const result = await dependencies.controller.cancelQueuedMessage(
        threadId,
        messageId,
        expectedQueueHash,
      );
      dependencies.store.completeIdempotent(scope, idempotencyKey, result);
      return reply.code(200).send({ ok: true, ...result });
    } catch (error) {
      dependencies.store.releaseIdempotent(scope, idempotencyKey);
      throw error;
    }
  });

  app.post("/v1/tasks/:threadId/interrupt", async (request, reply) => {
    const threadId = routeParam(request, "threadId");
    const body = asRecord(request.body);
    const turnId =
      readString(body?.expectedTurnId) ?? readString(body?.turnId) ?? undefined;
    await dependencies.controller.interrupt(threadId, turnId);
    return reply.code(202).send({ ok: true });
  });

  app.post("/v1/tasks/:threadId/request-push", async (request, reply) => {
    const threadId = routeParam(request, "threadId");
    if (asRecord(request.body)?.confirmed !== true) {
      return reply.code(400).send({ error: "push-confirmation-required" });
    }
    await dependencies.controller.requestPush(threadId);
    return reply.code(202).send({ ok: true });
  });

  app.post("/v1/approvals/:requestId", async (request, reply) => {
    const requestId = routeParam(request, "requestId");
    const decision = asRecord(request.body)?.decision;
    if (decision !== "accept" && decision !== "decline" && decision !== "cancel") {
      return reply.code(400).send({ error: "invalid-approval-decision" });
    }
    const device = (request as AuthenticatedRequest).device;
    if (!device) return reply.code(401).send({ error: "unauthorized" });
    await dependencies.controller.respondToApproval(
      requestId,
      decision,
      `${device.deviceId}:${readHeader(request, "x-request-id")}`,
    );
    return reply.code(200).send({ ok: true });
  });

  app.post("/v1/user-input/:requestId", async (request, reply) => {
    const requestId = routeParam(request, "requestId");
    const body = asRecord(request.body);
    const response = asRecord(body?.response);
    if (!response) {
      return reply.code(400).send({ error: "invalid-user-input-response" });
    }
    const device = (request as AuthenticatedRequest).device;
    if (!device) return reply.code(401).send({ error: "unauthorized" });
    await dependencies.controller.respondToUserInput(
      requestId,
      response,
      `${device.deviceId}:${readHeader(request, "x-request-id")}`,
    );
    return reply.code(200).send({ ok: true });
  });

  app.post("/v1/attachments", async (request, reply) => {
    const query = asRecord(request.query);
    const name = readString(query?.name);
    const mimeType = readString(query?.mimeType)?.toLowerCase();
    if (!name || !mimeType) {
      return reply.code(400).send({ error: "attachment-metadata-required" });
    }
    const actualMimeType = readHeader(request, "content-type")
      ?.split(";", 1)[0]
      ?.trim()
      .toLowerCase();
    if (actualMimeType !== mimeType) {
      return reply.code(400).send({ error: "attachment-content-type-mismatch" });
    }
    const body = Buffer.isBuffer(request.body) ? request.body : null;
    if (!body) return reply.code(400).send({ error: "attachment-body-required" });
    const device = authenticatedDevice(request);
    const idempotencyKey = readString(query?.idempotencyKey) ?? requiredRequestId(request);
    const scope = `${device.deviceId}:attachment-upload`;
    const claim = dependencies.store.beginIdempotent(scope, idempotencyKey);
    if (claim.replayed) {
      return reply.code(201).send({
        attachment: asRecord(claim.result),
        replayed: true,
      });
    }
    try {
      const attachment = await requireAttachmentStore(dependencies).save(
        device.deviceId,
        name,
        mimeType,
        body,
      );
      dependencies.store.completeIdempotent(scope, idempotencyKey, attachment);
      return reply.code(201).send({ attachment });
    } catch (error) {
      dependencies.store.releaseIdempotent(scope, idempotencyKey);
      throw error;
    }
  });

  app.delete("/v1/attachments/:attachmentId", async (request, reply) => {
    const attachmentId = routeParam(request, "attachmentId");
    const device = authenticatedDevice(request);
    const removed = await requireAttachmentStore(dependencies).remove(
      device.deviceId,
      attachmentId,
    );
    return reply.code(removed ? 200 : 404).send({ ok: removed });
  });

  app.post("/v1/voice", async (request, reply) => {
    const device = (request as AuthenticatedRequest).device;
    if (!device) return reply.code(401).send({ error: "unauthorized" });
    if (!dependencies.transcriber || !dependencies.transcriber.status().available) {
      return reply.code(503).send({ error: "whisper-not-configured" });
    }
    const audio = Buffer.isBuffer(request.body) ? request.body : null;
    if (!audio || audio.length === 0) {
      return reply.code(400).send({ error: "voice-audio-empty" });
    }
    const transcript = await dependencies.transcriber.transcribe(audio);
    const session = voiceSessions.create(device.deviceId, transcript);
    dependencies.store.appendEvent("voice.transcript", {
      voiceId: session.voiceId,
      transcript: session.transcript,
      expiresAt: session.expiresAt,
    });
    return reply.code(201).send({
      voiceId: session.voiceId,
      transcript: session.transcript,
      expiresAt: session.expiresAt,
    });
  });

  app.post("/v1/voice/:voiceId/send", async (request, reply) => {
    const voiceId = routeParam(request, "voiceId");
    const body = asRecord(request.body);
    const threadId = readString(body?.threadId);
    if (!threadId) return reply.code(400).send({ error: "thread-id-required" });
    const device = (request as AuthenticatedRequest).device;
    if (!device) return reply.code(401).send({ error: "unauthorized" });
    const claimant = `${device.deviceId}:${readHeader(request, "x-request-id")}`;
    const session = voiceSessions.claim(voiceId, claimant);
    const editedText = readString(body?.text);
    try {
      await dependencies.controller.sendMessage(
        threadId,
        editedText ?? session.transcript,
      );
      voiceSessions.cancel(voiceId);
      dependencies.store.appendEvent("voice.sent", { voiceId }, threadId);
      return reply.code(202).send({ ok: true });
    } catch (error) {
      voiceSessions.release(voiceId, claimant);
      throw error;
    }
  });

  app.delete("/v1/voice/:voiceId", async (request, reply) => {
    const voiceId = routeParam(request, "voiceId");
    const removed = voiceSessions.cancel(voiceId);
    dependencies.store.appendEvent("voice.cancelled", { voiceId });
    return reply.code(removed ? 200 : 404).send({ ok: removed });
  });

  return app;
}

function readHeader(request: FastifyRequest, name: string): string | null {
  const value = request.headers[name];
  return typeof value === "string" && value.length > 0 ? value : null;
}

function manageEventStream(
  socket: WebSocket,
  store: BridgeStore,
  heartbeatIntervalMs: number,
  maxBufferedBytes: number,
): void {
  let closed = false;
  let awaitingPong = false;
  let heartbeat: NodeJS.Timeout | null = null;
  let unsubscribe: () => void = () => undefined;

  const cleanup = () => {
    if (closed) return;
    closed = true;
    if (heartbeat) clearInterval(heartbeat);
    heartbeat = null;
    unsubscribe();
  };
  const terminate = () => {
    cleanup();
    if (socket.readyState !== socket.CLOSED) {
      try {
        socket.terminate();
      } catch {
        // The close/error path may have already destroyed the underlying socket.
      }
    }
  };
  const send = (value: unknown) => {
    if (closed || socket.readyState !== socket.OPEN) {
      terminate();
      return;
    }
    const payload = JSON.stringify(value);
    if (socket.bufferedAmount + Buffer.byteLength(payload) > maxBufferedBytes) {
      terminate();
      return;
    }
    try {
      socket.send(payload, (error) => {
        if (error) terminate();
      });
    } catch {
      terminate();
    }
  };

  socket.once("close", cleanup);
  socket.once("error", terminate);
  socket.on("pong", () => {
    awaitingPong = false;
  });
  unsubscribe = store.subscribe(send);
  for (const event of store.eventsAfter(0)) {
    send(event);
    if (closed) return;
  }

  heartbeat = setInterval(() => {
    if (awaitingPong || socket.readyState !== socket.OPEN) {
      terminate();
      return;
    }
    awaitingPong = true;
    try {
      socket.ping();
    } catch {
      terminate();
    }
  }, heartbeatIntervalMs);
  heartbeat.unref();
}

function positiveIntegerOption(
  value: number | undefined,
  fallback: number,
  error: string,
): number {
  if (value === undefined) return fallback;
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error(error);
  return value;
}

function parseRefreshQuery(value: unknown): boolean {
  if (value === undefined || value === false || value === 0 || value === "0" || value === "false") {
    return false;
  }
  if (value === true || value === 1 || value === "1" || value === "true") return true;
  throw new Error("invalid-model-refresh-query");
}

function parseTaskListQuery(
  query: Record<string, unknown> | null,
): TaskListQuery {
  const rawLimit = query?.limit;
  const limit = rawLimit === undefined ? undefined : Number(rawLimit);
  if (limit !== undefined && (!Number.isSafeInteger(limit) || limit < 1 || limit > 100)) {
    throw new Error("invalid-task-limit");
  }
  const rawArchived = query?.archived;
  let archived: boolean | undefined;
  if (rawArchived !== undefined) {
    if (rawArchived === true || rawArchived === "true") archived = true;
    else if (rawArchived === false || rawArchived === "false") archived = false;
    else throw new Error("invalid-archived-filter");
  }
  const cursor = readString(query?.cursor) ?? undefined;
  const searchTerm =
    readString(query?.query) ?? readString(query?.searchTerm) ?? undefined;
  return {
    ...(limit !== undefined ? { limit } : {}),
    ...(cursor ? { cursor } : {}),
    ...(archived !== undefined ? { archived } : {}),
    ...(searchTerm ? { searchTerm } : {}),
  };
}

function parseTaskHistoryQuery(
  query: Record<string, unknown> | null,
): { limit?: number; cursor?: string } {
  const rawLimit = query?.historyLimit;
  const cursor = readString(query?.historyCursor) ?? undefined;
  if (rawLimit === undefined && !cursor) return {};
  const limit = rawLimit === undefined ? 50 : Number(rawLimit);
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) {
    throw new Error("invalid-history-limit");
  }
  return {
    limit,
    ...(cursor ? { cursor } : {}),
  };
}

function parseDelivery(value: unknown): MessageDelivery {
  if (value === undefined || value === null || value === "") return "auto";
  if (value === "auto" || value === "start" || value === "steer" || value === "queue") {
    return value;
  }
  throw new Error("invalid-message-delivery");
}

function parseStringArray(value: unknown, error: string): string[] {
  if (value === undefined || value === null) return [];
  if (
    !Array.isArray(value) ||
    value.length > 10 ||
    !value.every((entry) => typeof entry === "string" && entry.length > 0)
  ) {
    throw new Error(error);
  }
  return value;
}

function authenticatedDevice(request: FastifyRequest): DeviceRecord {
  const device = (request as AuthenticatedRequest).device;
  if (!device) throw new Error("unauthorized");
  return device;
}

function requiredRequestId(request: FastifyRequest): string {
  const requestId = readHeader(request, "x-request-id");
  if (!requestId) throw new Error("missing-request-signature");
  return requestId;
}

function requireAttachmentStore(
  dependencies: BridgeAppDependencies,
): AttachmentStore {
  if (!dependencies.attachments) throw new Error("attachment-store-unavailable");
  return dependencies.attachments;
}

function routeParam(request: FastifyRequest, key: string): string {
  const value = asRecord(request.params)?.[key];
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`missing-route-param:${key}`);
  }
  return value;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function readString(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function isLoopbackAddress(address: string | undefined): boolean {
  if (!address) return false;
  return address === "127.0.0.1" || address === "::1" || address === "::ffff:127.0.0.1";
}
