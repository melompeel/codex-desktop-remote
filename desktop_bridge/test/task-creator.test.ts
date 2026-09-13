import { describe, expect, it, vi } from "vitest";

import {
  activateCodexThread,
  AppServerTaskCreator,
  type AppServerSession,
} from "../src/catalog/task-creator.js";

describe("AppServerTaskCreator", () => {
  it("activates a thread through the currently installed Desktop executable", async () => {
    const execute = vi.fn(async () => undefined);
    const currentDesktop =
      "C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.901.6511.0_x64__publisher\\app\\ChatGPT.exe";

    await activateCodexThread(`codex://threads/${THREAD_ID}`, {
      platform: "win32",
      resolveDesktopExecutable: async () => currentDesktop,
      execute,
    });

    expect(execute).toHaveBeenCalledWith(currentDesktop, [`codex://threads/${THREAD_ID}`]);
    expect(execute).not.toHaveBeenCalledWith("explorer.exe", expect.anything());
  });

  it("falls back to the registered URI handler when Desktop lookup is unavailable", async () => {
    const execute = vi.fn(async () => undefined);

    await activateCodexThread(`codex://threads/${THREAD_ID}`, {
      platform: "win32",
      resolveDesktopExecutable: async () => null,
      execute,
    });

    expect(execute).toHaveBeenCalledWith("explorer.exe", [`codex://threads/${THREAD_ID}`]);
  });

  it("falls back to the registered URI handler when the current Desktop cannot launch", async () => {
    const currentDesktop = "C:\\CurrentCodex\\app\\Codex.exe";
    const execute = vi.fn(async (executable: string) => {
      if (executable === currentDesktop) throw new Error("desktop-launch-failed");
    });

    await activateCodexThread(`codex://threads/${THREAD_ID}`, {
      platform: "win32",
      resolveDesktopExecutable: async () => currentDesktop,
      execute,
    });

    expect(execute.mock.calls).toEqual([
      [currentDesktop, [`codex://threads/${THREAD_ID}`]],
      ["explorer.exe", [`codex://threads/${THREAD_ID}`]],
    ]);
  });

  it("materializes, rolls back, unsubscribes, and activates a workspace task", async () => {
    const session = new FakeSession();
    const activate = vi.fn(async () => undefined);
    const creator = new AppServerTaskCreator(async () => session, activate);

    const result = await creator.materialize({
      cwd: "C:\\repo",
      model: "gpt-test",
      effort: "high",
    });

    expect(result).toEqual({
      threadId: THREAD_ID,
      projectId: "project-1",
      permissionProfile: ":workspace",
    });
    expect(session.call("thread/start")?.params).toMatchObject({
      cwd: "C:\\repo",
      projectId: "project-1",
      model: "gpt-test",
      permissions: ":workspace",
      approvalPolicy: "on-request",
      ephemeral: false,
    });
    expect(session.call("turn/start")?.params).toMatchObject({
      threadId: THREAD_ID,
      effort: "high",
      permissions: ":read-only",
      approvalPolicy: "on-request",
    });
    expect(session.calls.map((call) => call.method)).toEqual([
      "project/list",
      "permissionProfile/list",
      "thread/start",
      "turn/start",
      "thread/revert",
      "thread/read",
      "thread/unsubscribe",
    ]);
    expect(activate).toHaveBeenCalledWith(
      expect.stringMatching(new RegExp(`^codex://threads/${THREAD_ID}\\?follow=`)),
    );
    expect(session.disposed).toBe(true);
  });

  it("fails closed when the workspace permission profile is unavailable", async () => {
    const session = new FakeSession({ workspaceAllowed: false });
    const creator = new AppServerTaskCreator(async () => session, async () => undefined);

    await expect(creator.materialize({ cwd: "C:\\repo" })).rejects.toThrow(
      "task-permission-profile-unavailable::workspace",
    );
    expect(session.call("thread/start")).toBeUndefined();
    expect(session.disposed).toBe(true);
  });

  it("deletes a partially created task when bootstrap completion fails", async () => {
    const session = new FakeSession({ turnStatus: "failed" });
    const creator = new AppServerTaskCreator(async () => session, async () => undefined);

    await expect(creator.materialize({ cwd: "C:\\repo" })).rejects.toThrow(
      "task-bootstrap-failed",
    );
    expect(session.call("thread/delete")?.params).toEqual({ threadId: THREAD_ID });
    expect(session.disposed).toBe(true);
  });

  it("refuses handoff when the bootstrap turn remains after revert", async () => {
    const session = new FakeSession({ retainedBootstrap: true });
    const activate = vi.fn(async () => undefined);
    const creator = new AppServerTaskCreator(async () => session, activate);

    await expect(creator.materialize({ cwd: "C:\\repo" })).rejects.toThrow(
      "task-bootstrap-rollback-unverified",
    );
    expect(activate).not.toHaveBeenCalled();
    expect(session.call("thread/delete")?.params).toEqual({ threadId: THREAD_ID });
  });

  it("allows a retry when opening the helper app-server fails", async () => {
    const session = new FakeSession();
    let attempts = 0;
    const creator = new AppServerTaskCreator(async () => {
      attempts += 1;
      if (attempts === 1) throw new Error("helper-start-failed");
      return session;
    }, async () => undefined);

    await expect(creator.materialize({ cwd: "C:\\repo" })).rejects.toThrow(
      "helper-start-failed",
    );
    await expect(creator.materialize({ cwd: "C:\\repo" })).resolves.toMatchObject({
      threadId: THREAD_ID,
    });
  });

  it("materializes a durable quick conversation without a workspace", async () => {
    const session = new FakeSession();
    const activate = vi.fn(async () => undefined);
    const creator = new AppServerTaskCreator(async () => session, activate);

    await creator.materialize({ mode: "quick", model: "gpt-test", effort: "low" });

    expect(session.calls.map((call) => call.method)).toEqual([
      "thread/start",
      "turn/start",
      "thread/revert",
      "thread/read",
      "thread/unsubscribe",
    ]);
    expect(session.call("thread/start")?.params).not.toHaveProperty("cwd");
    expect(session.call("thread/start")?.params).not.toHaveProperty("projectId");
  });
});

const THREAD_ID = "01a0705b-c5c1-7d00-95bc-efd02a96789b";

class FakeSession implements AppServerSession {
  readonly calls: Array<{ method: string; params: Record<string, unknown> }> = [];
  disposed = false;

  constructor(private readonly options: {
    workspaceAllowed?: boolean;
    turnStatus?: string;
    retainedBootstrap?: boolean;
  } = {}) {}

  async request(method: string, params: Record<string, unknown>): Promise<Record<string, unknown>> {
    this.calls.push({ method, params });
    if (method === "project/list") {
      return {
        data: [{ id: "project-1", roots: [{ path: "C:\\repo\\" }] }],
      };
    }
    if (method === "permissionProfile/list") {
      return {
        data: [
          { id: ":read-only", allowed: true },
          { id: ":workspace", allowed: this.options.workspaceAllowed !== false },
        ],
      };
    }
    if (method === "thread/start") return { thread: { id: THREAD_ID } };
    if (method === "turn/start") return { turn: { id: "turn-bootstrap" } };
    if (method === "thread/read") {
      return {
        thread: {
          id: THREAD_ID,
          turns: this.options.retainedBootstrap ? [{ id: "turn-bootstrap" }] : [],
        },
      };
    }
    return {};
  }

  async waitForNotification(): Promise<Record<string, unknown>> {
    return {
      threadId: THREAD_ID,
      turn: { id: "turn-bootstrap", status: this.options.turnStatus ?? "completed" },
    };
  }

  dispose(): void {
    this.disposed = true;
  }

  call(method: string) {
    return this.calls.find((call) => call.method === method);
  }
}
