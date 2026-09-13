import { createHash } from "node:crypto";

import { describe, expect, it } from "vitest";

import {
  presentThread,
  presentThreadMetadata,
  presentThreadDiff,
  resolveThreadMedia,
  resolveThreadResource,
  sanitizeTerminalText,
} from "../src/domain/presentation.js";

describe("task presentation", () => {
  it("uses the git repository root when a quick conversation has no cwd", () => {
    expect(presentThreadMetadata({
      gitInfo: { repositoryRoot: "C:\\workspace\\demo" },
    })).toMatchObject({
      cwd: "C:\\workspace\\demo",
      cwdGroupLabel: "demo",
    });
  });

  it("summarizes messages and file changes without sending full diffs", () => {
    const detail = presentThread({
      threadId: "thread-1",
      revision: 8,
      state: {
        title: "Demo",
        threadRuntimeStatus: { type: "idle" },
        turns: [{
          id: "turn-1",
          status: "completed",
          items: [
            { id: "a", type: "agentMessage", text: "完成" },
            {
              id: "f",
              type: "fileChange",
              changes: [{ path: "src/app.ts", diff: "SECRET-DIFF" }],
            },
          ],
        }],
      },
    });

    expect(detail).toMatchObject({
      threadId: "thread-1",
      revision: 8,
      title: "Demo",
      items: [
        { kind: "assistant", text: "完成" },
        { kind: "file", text: "src/app.ts" },
      ],
    });
    expect(JSON.stringify(detail)).not.toContain("SECRET-DIFF");
  });

  it("exposes referenced ImageView files through opaque media ids", () => {
    const thread = {
      threadId: "thread-image",
      revision: 12,
      state: {
        turns: [{
          id: "turn-image",
          status: "completed",
          items: [{
            id: "image-1",
            type: "ImageView",
            path: "file:///C:/Users/melon/My%20Project/result.png",
          }],
        }],
      },
    };

    const detail = presentThread(thread);
    const media = detail.items[0]?.media;

    expect(detail.items[0]).toMatchObject({
      kind: "image",
      text: "result.png",
      media: {
        name: "result.png",
        mimeType: "image/png",
      },
    });
    expect(media?.mediaId).toMatch(/^[a-f0-9]{32}$/);
    expect(JSON.stringify(detail)).not.toContain("C:/Users");
    expect(JSON.stringify(detail)).not.toContain("fsPath");
    expect(resolveThreadMedia(thread, media!.mediaId)).toEqual({
      ...media,
      fsPath: "C:\\Users\\melon\\My Project\\result.png",
    });
  });

  it("keeps user-uploaded images beside their message in the remote timeline", () => {
    const thread = {
      threadId: "thread-user-image",
      revision: 7,
      state: {
        turns: [{
          id: "turn-user-image",
          status: "completed",
          items: [{
            id: "user-image-message",
            type: "userMessage",
            content: [
              { type: "text", text: "请查看这张图" },
              { type: "localImage", path: "C:\\bridge\\screen.png" },
            ],
          }],
        }],
      },
    };

    const detail = presentThread(thread);

    expect(detail.items.map((item) => item.kind)).toEqual(["user", "userImage"]);
    expect(detail.items[1]).toMatchObject({
      kind: "userImage",
      text: "screen.png",
      media: { name: "screen.png", mimeType: "image/png" },
    });
    const media = detail.items[1]?.media;
    expect(resolveThreadMedia(thread, media!.mediaId)).toEqual({
      ...media,
      fsPath: "C:\\bridge\\screen.png",
    });
  });

  it("keeps v11 canonical user images and exposes turn presentation metadata", () => {
    const thread = {
      threadId: "thread-canonical-user-image",
      revision: 19,
      state: {
        turnHistory: {
          kind: "canonical",
          history: {
            entitiesByKey: {
              "turn:one": {
                turnId: "turn-one",
                status: "completed",
                durationMs: 75432,
                params: {
                  input: [
                    { type: "text", text: "查看图片" },
                    { type: "localImage", path: "C:\\bridge\\phone.png" },
                  ],
                  attachments: [{
                    label: "phone.png",
                    path: "C:\\bridge\\phone.png",
                    fsPath: "C:\\bridge\\phone.png",
                  }],
                },
                items: [{
                  id: "user-message-one",
                  type: "userMessage",
                  content: [
                    { type: "text", text: "查看图片" },
                    { type: "localImage", detail: null, path: "C:\\bridge\\phone.png" },
                  ],
                }],
              },
            },
            islands: [{ entries: [{ value: "turn:one" }] }],
          },
        },
      },
    };

    const detail = presentThread(thread);

    expect(detail.items).toMatchObject([
      {
        kind: "user",
        sourceItemId: "user-message-one",
        turnDurationMs: 75432,
      },
      {
        kind: "userImage",
        sourceItemId: "user-message-one",
        turnDurationMs: 75432,
        media: { name: "phone.png", mimeType: "image/png" },
      },
    ]);
    const media = detail.items[1]?.media;
    expect(resolveThreadMedia(thread, media!.mediaId)).toEqual({
      ...media,
      fsPath: "C:\\bridge\\phone.png",
    });
  });

  it("does not trim an early user image from a long-running turn", () => {
    const processItems = Array.from({ length: 240 }, (_, index) => ({
      id: `reasoning-${index}`,
      type: "reasoning",
      summary: [`step ${index}`],
    }));
    const thread = {
      threadId: "thread-long-image",
      revision: 31,
      state: {
        turns: [{
          id: "turn-long",
          status: "completed",
          items: [
            {
              id: "user-long",
              type: "userMessage",
              content: [
                { type: "text", text: "请查看长任务截图" },
                { type: "localImage", path: "C:\\bridge\\long-task.png" },
              ],
            },
            ...processItems,
            { id: "final-long", type: "agentMessage", text: "处理完成" },
          ],
        }],
      },
    };

    const detail = presentThread(thread);

    expect(detail.items).toHaveLength(200);
    expect(detail.items.map((item) => item.kind)).toContain("userImage");
    expect(detail.items.find((item) => item.kind === "user")?.text).toBe("请查看长任务截图");
    expect(detail.items.at(-1)).toMatchObject({ kind: "assistant", text: "处理完成" });
  });

  it("reserves timeline capacity for process details in a long conversation", () => {
    const turns = Array.from({ length: 120 }, (_, index) => ({
      id: `turn-${index}`,
      status: "completed",
      durationMs: 60_000 + index,
      items: [
        { id: `user-${index}`, type: "userMessage", content: `request ${index}` },
        { id: `reasoning-${index}`, type: "reasoning", summary: [`step ${index}`] },
        { id: `final-${index}`, type: "agentMessage", text: `result ${index}` },
      ],
    }));

    const detail = presentThread({
      threadId: "thread-long-conversation",
      revision: 120,
      state: { turns },
    });

    expect(detail.items).toHaveLength(200);
    expect(detail.items.some((item) => item.kind === "status")).toBe(true);
    expect(detail.items).toContainEqual(expect.objectContaining({
      kind: "status",
      text: "step 119",
      turnDurationMs: 60_119,
    }));
    expect(detail.items.at(-1)).toMatchObject({ kind: "assistant", text: "result 119" });
  });

  it("pages recent history while anchoring the latest user request", () => {
    const currentProcess = Array.from({ length: 70 }, (_, index) => ({
      id: `current-step-${index}`,
      type: "reasoning",
      summary: [`current step ${index}`],
    }));
    const thread = {
      threadId: "thread-paged-history",
      revision: 9,
      state: {
        turns: [
          {
            id: "turn-older",
            status: "completed",
            items: [
              { id: "older-user", type: "userMessage", content: "older request" },
              ...Array.from({ length: 8 }, (_, index) => ({
                id: `older-step-${index}`,
                type: "reasoning",
                summary: [`older step ${index}`],
              })),
              { id: "older-final", type: "agentMessage", text: "older result" },
            ],
          },
          {
            id: "turn-current",
            status: "completed",
            durationMs: 90_000,
            items: [
              { id: "current-user", type: "userMessage", content: "latest request" },
              ...currentProcess,
              { id: "current-final", type: "agentMessage", text: "latest result" },
            ],
          },
        ],
      },
    };

    const latest = presentThread(thread, { limit: 50 });

    expect(latest.items).toHaveLength(50);
    expect(latest.items[0]).toMatchObject({ kind: "user", text: "latest request" });
    expect(latest.items.at(-1)).toMatchObject({ kind: "assistant", text: "latest result" });
    expect(latest.hasMoreHistory).toBe(true);
    expect(latest.historyCursor).toEqual(expect.any(String));

    const older = presentThread(thread, { limit: 50, cursor: latest.historyCursor! });
    expect(older.items[0]).toMatchObject({ kind: "user", text: "older request" });
    expect(older.hasMoreHistory).toBe(false);
  });

  it("turns assistant markdown images into ordered remote media items", () => {
    const thread = {
      threadId: "thread-markdown-image",
      revision: 13,
      state: {
        cwd: "C:\\workspace",
        turns: [{
          id: "turn-image",
          status: "completed",
          items: [{
            id: "message-image",
            type: "agentMessage",
            text: [
              "生成结果如下：",
              "![最终效果](<file:///C:/Users/melon/Exports/final%20result.png>)",
              "图片之后的说明。",
            ].join("\n\n"),
          }],
        }],
      },
    };

    const detail = presentThread(thread);

    expect(detail.items.map((item) => item.kind)).toEqual([
      "assistant",
      "image",
      "assistant",
    ]);
    expect(detail.items.map((item) => item.text)).toEqual([
      "生成结果如下：",
      "final result.png",
      "图片之后的说明。",
    ]);
    expect(JSON.stringify(detail)).not.toContain("C:/Users");
    expect(JSON.stringify(detail)).not.toContain("fsPath");
    const media = detail.items[1]?.media;
    expect(media).toMatchObject({
      name: "final result.png",
      mimeType: "image/png",
    });
    expect(resolveThreadMedia(thread, media!.mediaId)).toEqual({
      ...media,
      fsPath: "C:\\Users\\melon\\Exports\\final result.png",
    });
  });

  it("rewrites local markdown file links to authenticated task resources", () => {
    const thread = {
      threadId: "thread-resource",
      revision: 3,
      state: {
        turns: [{
          id: "turn-resource",
          status: "completed",
          items: [{
            id: "message-resource",
            type: "agentMessage",
            text: "查看 [报告](<file:///C:/Users/melon/My%20Project/report.pdf>) 和 [官网](https://openai.com)",
          }],
        }],
      },
    };

    const detail = presentThread(thread);
    const resource = detail.items[0]?.resources?.[0];

    expect(detail.items[0]?.text).toContain(
      `[报告](codexremote://resource/${resource?.resourceId})`,
    );
    expect(detail.items[0]?.text).toContain("[官网](https://openai.com)");
    expect(JSON.stringify(detail)).not.toContain("C:/Users");
    expect(JSON.stringify(detail)).not.toContain("fsPath");
    expect(resolveThreadResource(thread, resource!.resourceId)).toEqual({
      ...resource,
      fsPath: "C:\\Users\\melon\\My Project\\report.pdf",
    });
  });

  it("does not register user-supplied local links as downloadable resources", () => {
    const thread = {
      threadId: "thread-user-resource",
      revision: 4,
      state: {
        turns: [{
          id: "turn-resource",
          status: "completed",
          items: [
            {
              id: "user-resource",
              type: "userMessage",
              text: "读取 [密钥](<file:///C:/Users/melon/.ssh/id_rsa>)",
            },
            {
              id: "assistant-resource",
              type: "agentMessage",
              text: "查看 [导出报告](<file:///C:/Users/melon/Exports/report.pdf>)",
            },
          ],
        }],
      },
    };

    const detail = presentThread(thread);

    expect(detail.items[0]?.resources).toBeUndefined();
    expect(detail.items[0]?.text).toContain("file:///C:/Users/melon/.ssh/id_rsa");
    expect(detail.items[1]?.resources).toHaveLength(1);
    expect(detail.items[1]?.text).toContain("codexremote://resource/");
    expect(resolveThreadResource(
      thread,
      opaqueId(
        "thread-user-resource",
        "user-resource",
        "C:\\Users\\melon\\.ssh\\id_rsa",
      ),
    )).toBeNull();
  });

  it("rejects UNC files and images while keeping local assistant resources", () => {
    const thread = {
      threadId: "thread-unc",
      revision: 5,
      state: {
        turns: [{
          id: "turn-unc",
          status: "completed",
          items: [
            {
              id: "assistant-unc",
              type: "agentMessage",
              text: [
                "[共享文档](<file://server/share/report.txt>)",
                "![共享图片](<file://server/share/result.png>)",
                "[本地文档](<file:///C:/Users/melon/Exports/report.txt>)",
              ].join("\n"),
            },
            {
              id: "image-unc",
              type: "ImageView",
              path: "\\\\server\\share\\result.png",
            },
          ],
        }],
      },
    };

    const detail = presentThread(thread);

    expect(detail.items.filter((item) => item.kind === "image")).toHaveLength(0);
    expect(detail.items[0]?.resources).toHaveLength(1);
    expect(detail.items[0]?.resources?.[0]?.name).toBe("report.txt");
    expect(detail.items[0]?.text).toContain("file://server/share/report.txt");
    expect(detail.items[0]?.text).toContain("file://server/share/result.png");
    expect(resolveThreadMedia(
      thread,
      opaqueId("thread-unc", "image-unc", "\\\\server\\share\\result.png"),
    )).toBeNull();
  });

  it("reads v11 canonical turnHistory entities in island order", () => {
    const detail = presentThread({
      threadId: "thread-v11",
      revision: 2,
      state: {
        title: "Canonical",
        turns: [],
        turnHistory: {
          kind: "canonical",
          history: {
            entitiesByKey: {
              "turn:a": {
                turnId: "a",
                status: "completed",
                items: [{ id: "m1", type: "agentMessage", text: "first" }],
              },
              "turn:b": {
                turnId: "b",
                status: "inProgress",
                items: [{ id: "m2", type: "agentMessage", text: "second" }],
              },
            },
            islands: [{ entries: [{ key: "one", value: "turn:a" }, { key: "two", value: "turn:b" }] }],
          },
        },
      },
    });

    expect(detail.items.map((item) => item.text)).toEqual(["first", "second"]);
  });

  it("removes terminal escape sequences while preserving readable command output", () => {
    const detail = presentThread({
      threadId: "thread-terminal",
      revision: 3,
      state: {
        title: "Terminal output",
        turns: [{
          id: "turn-command",
          status: "completed",
          items: [{
            id: "command-1",
            type: "commandExecution",
            command: "npm test",
            aggregatedOutput: "\u001b[?25l\u001b[2J31 tests passed\u0000\r\n\u001b[0mDone",
          }],
        }],
      },
    });

    expect(detail.items).toMatchObject([{
      kind: "command",
      text: "$ npm test\n31 tests passed\nDone",
    }]);
    expect(detail.items[0]?.text).not.toContain("\u001b");
  });

  it("strips terminal control families without altering tabs or backslashes", () => {
    const controlled = [
      "before\rmiddle\r\nafter\tC:\\repo\\file.ts",
      "\u001b[?25lCSI\u001b[0m",
      "\u001b]0;window title\u0007OSC",
      "\u001bP1;2|device payload\u001b\\DCS",
      "\u001bXprivate\u001b\\SOS",
      "\u001b^private\u001b\\PM",
      "\u001b_private\u001b\\APC",
      "\u001b(Bcharset",
      "\u009b31mC1-CSI\u009b0m",
      "nul\u0000del\u007f",
    ].join("\n");

    expect(sanitizeTerminalText(controlled)).toBe([
      "before\nmiddle\nafter\tC:\\repo\\file.ts",
      "CSI",
      "OSC",
      "DCS",
      "SOS",
      "PM",
      "APC",
      "charset",
      "C1-CSI",
      "nuldel",
    ].join("\n"));
  });

  it("exposes workspace grouping, git metadata, settings, and the active turn", () => {
    const detail = presentThread({
      threadId: "thread-meta",
      revision: 4,
      state: {
        cwd: "C:\\Users\\melon\\repo\\",
        gitInfo: {
          branch: "feature/mobile",
          repositoryRoot: "C:\\Users\\melon\\repo",
          sha: "abc123",
          isDirty: true,
          remoteUrl: "https://secret@example.invalid/repo.git",
        },
        latestThreadSettings: {
          model: "gpt-5.2-codex",
          effort: "high",
          serviceTier: "priority",
          hiddenSetting: "not-exposed",
        },
        turnHistory: {
          history: {
            entitiesByKey: {
              active: { turnId: "turn-active", status: "inProgress", items: [] },
            },
            islands: [{ entries: [{ value: "active" }] }],
          },
        },
      },
    });

    expect(detail).toMatchObject({
      cwd: "C:\\Users\\melon\\repo\\",
      cwdGroupKey: "c:/users/melon/repo",
      cwdGroupLabel: "repo",
      gitInfo: {
        branch: "feature/mobile",
        repositoryRoot: "C:\\Users\\melon\\repo",
        sha: "abc123",
        isDirty: true,
      },
      settings: {
        model: "gpt-5.2-codex",
        effort: "high",
        serviceTier: "priority",
      },
      activeTurnId: "turn-active",
    });
    expect(JSON.stringify(detail)).not.toContain("remoteUrl");
    expect(JSON.stringify(detail)).not.toContain("hiddenSetting");
  });

  it("returns complete turn and per-file unified diffs through the dedicated view", () => {
    const fullTurnDiff = "diff --git a/a.ts b/a.ts\n--- a/a.ts\n+++ b/a.ts\n@@ -1 +1 @@\n-old\n+new\n";
    const fullFileDiff = "diff --git a/b.ts b/b.ts\n--- a/b.ts\n+++ b/b.ts\n@@ -0,0 +1 @@\n+added\n";
    const diff = presentThreadDiff({
      threadId: "thread-diff",
      revision: 9,
      state: {
        turns: [{
          id: "turn-1",
          status: "completed",
          diff: fullTurnDiff,
          items: [{
            id: "file-item",
            type: "fileChange",
            changes: [{
              path: "src/b.ts",
              kind: "add",
              unifiedDiff: fullFileDiff,
            }],
          }],
        }],
      },
    });

    expect(diff).toEqual({
      threadId: "thread-diff",
      revision: 9,
      turns: [{ turnId: "turn-1", status: "completed", unifiedDiff: fullTurnDiff }],
      files: [{
        turnId: "turn-1",
        itemId: "file-item",
        path: "src/b.ts",
        kind: "add",
        unifiedDiff: fullFileDiff,
      }],
    });
  });
});

function opaqueId(threadId: string, itemId: string, path: string): string {
  return createHash("sha256")
    .update(`${threadId}\0${itemId}\0${path}`)
    .digest("hex")
    .slice(0, 32);
}
