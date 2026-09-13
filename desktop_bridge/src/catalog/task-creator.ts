import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { randomUUID } from "node:crypto";

import { findCodexDesktopAppExecutable } from "../runtime/desktop.js";
const BOOTSTRAP_PROMPT =
  "Create an empty durable task for Desktop handoff. Reply with READY only. " +
  "Do not call tools, inspect files, or modify the workspace.";

type JsonRecord = Record<string, unknown>;

export type MaterializeTaskInput = {
  cwd?: string;
  mode?: "project" | "quick";
  model?: string;
  effort?: string;
};

export type MaterializedTask = {
  threadId: string;
  projectId?: string;
  permissionProfile?: string;
};

export interface AppServerSession {
  request(method: string, params: JsonRecord): Promise<JsonRecord>;
  waitForNotification(
    method: string,
    predicate: (params: JsonRecord) => boolean,
    timeoutMs?: number,
  ): Promise<JsonRecord>;
  dispose(): void;
}

export type AppServerSessionFactory = () => Promise<AppServerSession>;
export type ThreadActivator = (url: string) => Promise<void>;
export type DesktopActivationOptions = {
  platform?: NodeJS.Platform;
  resolveDesktopExecutable?: () => Promise<string | null>;
  execute?: (executable: string, args: string[]) => Promise<void>;
};

export class AppServerTaskCreator {
  private creating = false;

  constructor(
    private readonly openSession: AppServerSessionFactory,
    private readonly activate: ThreadActivator = activateCodexThread,
    private readonly turnTimeoutMs = 120_000,
  ) {}

  async materialize(input: MaterializeTaskInput): Promise<MaterializedTask> {
    if (this.creating) throw new Error("task-creation-in-progress");
    const cwd = input.cwd?.trim() ?? "";
    const mode = input.mode ?? (cwd ? "project" : "quick");
    if (mode === "project" && !cwd) throw new Error("task-cwd-required");
    this.creating = true;
    let session: AppServerSession | null = null;
    let threadId: string | null = null;
    try {
      session = await this.openSession();
      const [projects, profiles] = mode === "project"
        ? await Promise.all([
          session.request("project/list", { limit: 100 }),
          session.request("permissionProfile/list", { cwd, limit: 100 }),
        ])
        : [null, null] as const;
      const workspaceProfile = profiles
        ? selectAllowedProfile(profiles, ":workspace")
        : undefined;
      const bootstrapProfile = profiles
        ? selectAllowedProfile(profiles, ":read-only", false) ?? workspaceProfile
        : undefined;
      const projectId = mode === "project" && projects
        ? selectProjectId(projects, cwd, false)
        : undefined;

      const started = await session.request("thread/start", compact({
        cwd: cwd || undefined,
        projectId,
        runtimeWorkspaceRoots: cwd ? [cwd] : undefined,
        model: input.model,
        permissions: workspaceProfile,
        approvalPolicy: "on-request",
        approvalsReviewer: "user",
        ephemeral: false,
        historyMode: "paginated",
        threadSource: "user",
        allowProviderModelFallback: false,
      }));
      threadId = readString(asRecord(started.thread)?.id);
      if (!threadId) throw new Error("task-thread-id-missing");

      const turnStarted = await session.request("turn/start", compact({
        threadId,
        cwd: cwd || undefined,
        model: input.model,
        effort: input.effort,
        permissions: bootstrapProfile,
        approvalPolicy: "on-request",
        approvalsReviewer: "user",
        turnTrigger: "user",
        input: [{ type: "text", text: BOOTSTRAP_PROMPT, text_elements: [] }],
      }));
      const turnId = readString(asRecord(turnStarted.turn)?.id);
      if (!turnId) throw new Error("task-bootstrap-turn-id-missing");
      const completed = await session.waitForNotification(
        "turn/completed",
        (params) =>
          readString(params.threadId) === threadId &&
          readString(asRecord(params.turn)?.id) === turnId,
        this.turnTimeoutMs,
      );
      const turn = asRecord(completed.turn);
      if (turn?.status !== "completed") {
        throw new Error(`task-bootstrap-${readString(turn?.status) ?? "failed"}`);
      }

      await session.request("thread/revert", { threadId, beforeTurnId: turnId });
      const verified = await session.request("thread/read", {
        threadId,
        includeTurns: true,
      });
      const verifiedThread = asRecord(verified.thread);
      if (
        readString(verifiedThread?.id) !== threadId ||
        !Array.isArray(verifiedThread?.turns) ||
        verifiedThread.turns.length !== 0
      ) {
        throw new Error("task-bootstrap-rollback-unverified");
      }
      await session.request("thread/unsubscribe", { threadId });

      const deepLink = codexThreadUrl(threadId);
      try {
        await this.activate(deepLink);
      } catch (error) {
        await bestEffortDelete(session, threadId);
        throw error;
      }
      return {
        threadId,
        ...(projectId ? { projectId } : {}),
        ...(workspaceProfile ? { permissionProfile: workspaceProfile } : {}),
      };
    } catch (error) {
      if (threadId && session) await bestEffortDelete(session, threadId);
      throw error;
    } finally {
      session?.dispose();
      this.creating = false;
    }
  }
}

export async function openStdioAppServer(
  executable: string,
  timeoutMs = 15_000,
): Promise<AppServerSession> {
  const session = new StdioAppServerSession(executable, timeoutMs);
  await session.initialize();
  return session;
}

export function codexThreadUrl(threadId: string): string {
  if (!/^[0-9a-f-]{20,}$/i.test(threadId)) throw new Error("invalid-task-thread-id");
  return `codex://threads/${encodeURIComponent(threadId)}?follow=${randomUUID()}`;
}

export async function activateCodexThread(
  url: string,
  options: DesktopActivationOptions = {},
): Promise<void> {
  if ((options.platform ?? process.platform) !== "win32") {
    throw new Error("desktop-activation-unsupported");
  }
  const execute = options.execute ?? launchDetached;
  const resolveDesktopExecutable =
    options.resolveDesktopExecutable ?? findCodexDesktopAppExecutable;
  const desktopExecutable = await resolveDesktopExecutable().catch(() => null);
  if (desktopExecutable) {
    try {
      await execute(desktopExecutable, [url]);
      return;
    } catch {}
  }
  await execute("explorer.exe", [url]);
}

function launchDetached(executable: string, args: string[]): Promise<void> {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, {
      detached: true,
      stdio: "ignore",
      windowsHide: true,
    });
    child.once("error", reject);
    child.once("spawn", () => {
      child.unref();
      resolve();
    });
  });
}

type PendingRequest = {
  resolve: (value: JsonRecord) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
};

type NotificationWaiter = {
  method: string;
  predicate: (params: JsonRecord) => boolean;
  resolve: (params: JsonRecord) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
};

class StdioAppServerSession implements AppServerSession {
  private readonly child: ChildProcessWithoutNullStreams;
  private readonly pending = new Map<string, PendingRequest>();
  private readonly waiters = new Set<NotificationWaiter>();
  private readonly notifications: Array<{ method: string; params: JsonRecord }> = [];
  private incoming = "";
  private closed = false;

  constructor(
    executable: string,
    private readonly timeoutMs: number,
  ) {
    this.child = spawn(executable, ["app-server", "--listen", "stdio://"], {
      stdio: ["pipe", "pipe", "pipe"],
      windowsHide: true,
    });
    this.child.stdout.setEncoding("utf8");
    this.child.stdout.on("data", (chunk: string) => this.handleData(chunk));
    this.child.stderr.on("data", () => undefined);
    this.child.on("error", (error) => this.fail(error));
    this.child.on("exit", (code) => {
      if (!this.closed) this.fail(new Error(`app-server-task-creator-exited:${code ?? "unknown"}`));
    });
  }

  async initialize(): Promise<void> {
    await this.request("initialize", {
      clientInfo: {
        name: "codex-android-bridge",
        title: "Codex Android Bridge",
        version: "0.2.0",
      },
      capabilities: { experimentalApi: true },
    });
    this.child.stdin.write(`${JSON.stringify({ method: "initialized", params: {} })}\n`);
  }

  request(method: string, params: JsonRecord): Promise<JsonRecord> {
    if (this.closed || !this.child.stdin.writable) {
      return Promise.reject(new Error("app-server-task-creator-not-running"));
    }
    const id = randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`app-server-task-creator-timeout:${method}`));
      }, this.timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.child.stdin.write(`${JSON.stringify({ id, method, params })}\n`);
    });
  }

  waitForNotification(
    method: string,
    predicate: (params: JsonRecord) => boolean,
    timeoutMs = this.timeoutMs,
  ): Promise<JsonRecord> {
    const existing = this.notifications.find(
      (notification) => notification.method === method && predicate(notification.params),
    );
    if (existing) return Promise.resolve(existing.params);
    if (this.closed) return Promise.reject(new Error("app-server-task-creator-not-running"));
    return new Promise((resolve, reject) => {
      const waiter: NotificationWaiter = {
        method,
        predicate,
        resolve,
        reject,
        timer: setTimeout(() => {
          this.waiters.delete(waiter);
          reject(new Error(`app-server-task-creator-timeout:${method}`));
        }, timeoutMs),
      };
      this.waiters.add(waiter);
    });
  }

  dispose(): void {
    if (this.closed) return;
    this.closed = true;
    this.fail(new Error("app-server-task-creator-disposed"));
    this.child.kill();
  }

  private handleData(chunk: string): void {
    this.incoming += chunk;
    for (;;) {
      const newline = this.incoming.indexOf("\n");
      if (newline < 0) return;
      const line = this.incoming.slice(0, newline).trim();
      this.incoming = this.incoming.slice(newline + 1);
      if (!line) continue;
      let message: unknown;
      try {
        message = JSON.parse(line);
      } catch {
        continue;
      }
      if (!isRecord(message)) continue;
      const id = typeof message.id === "string" || typeof message.id === "number"
        ? String(message.id)
        : null;
      if (id && ("result" in message || "error" in message)) {
        this.handleResponse(id, message);
        continue;
      }
      const method = readString(message.method);
      const params = asRecord(message.params) ?? {};
      if (id && method) {
        this.child.stdin.write(`${JSON.stringify({
          id: message.id,
          error: {
            code: -32601,
            message: "Task bootstrap cannot service app-server requests",
          },
        })}\n`);
        this.fail(new Error(`task-bootstrap-server-request:${method}`));
        continue;
      }
      if (method) this.handleNotification(method, params);
    }
  }

  private handleResponse(id: string, message: JsonRecord): void {
    const pending = this.pending.get(id);
    if (!pending) return;
    clearTimeout(pending.timer);
    this.pending.delete(id);
    const error = asRecord(message.error);
    if (error) {
      pending.reject(new Error(readString(error.message) ?? "app-server-task-creator-error"));
      return;
    }
    pending.resolve(asRecord(message.result) ?? {});
  }

  private handleNotification(method: string, params: JsonRecord): void {
    this.notifications.push({ method, params });
    if (this.notifications.length > 200) this.notifications.shift();
    for (const waiter of this.waiters) {
      if (waiter.method !== method || !waiter.predicate(params)) continue;
      clearTimeout(waiter.timer);
      this.waiters.delete(waiter);
      waiter.resolve(params);
    }
  }

  private fail(error: Error): void {
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(error);
      this.pending.delete(id);
    }
    for (const waiter of this.waiters) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
      this.waiters.delete(waiter);
    }
  }
}

function selectProjectId(result: JsonRecord, cwd: string, required = true): string | undefined {
  const projects = Array.isArray(result.data) ? result.data.filter(isRecord) : [];
  const expected = normalizedPath(cwd);
  for (const project of projects) {
    const roots = Array.isArray(project.roots) ? project.roots.filter(isRecord) : [];
    if (roots.some((root) => normalizedPath(readString(root.path) ?? "") === expected)) {
      const id = readString(project.id);
      if (id) return id;
    }
  }
  if (required) throw new Error("task-project-not-found");
  return undefined;
}

function selectAllowedProfile(result: JsonRecord, id: string): string;
function selectAllowedProfile(
  result: JsonRecord,
  id: string,
  required: false,
): string | null;
function selectAllowedProfile(
  result: JsonRecord,
  id: string,
  required = true,
): string | null {
  const profiles = Array.isArray(result.data) ? result.data.filter(isRecord) : [];
  const found = profiles.find(
    (profile) => readString(profile.id) === id && profile.allowed === true,
  );
  if (found) return id;
  if (required) throw new Error(`task-permission-profile-unavailable:${id}`);
  return null;
}

async function bestEffortDelete(session: AppServerSession, threadId: string): Promise<void> {
  try {
    await session.request("thread/delete", { threadId });
  } catch {}
}

function normalizedPath(value: string): string {
  const normalized = value.replace(/[\\/]+$/, "").replaceAll("/", "\\");
  return process.platform === "win32" ? normalized.toLocaleLowerCase("en-US") : normalized;
}

function compact(value: JsonRecord): JsonRecord {
  return Object.fromEntries(
    Object.entries(value).filter(([, entry]) => entry !== undefined),
  );
}

function asRecord(value: unknown): JsonRecord | null {
  return isRecord(value) ? value : null;
}

function isRecord(value: unknown): value is JsonRecord {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function readString(value: unknown): string | null {
  return typeof value === "string" && value.length > 0 ? value : null;
}
