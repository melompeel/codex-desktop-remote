import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { afterEach, describe, expect, it } from "vitest";

import {
  listWorkspaceFiles,
  readWorkspaceAttachment,
} from "../src/workspace/files.js";

const roots: string[] = [];

afterEach(async () => {
  await Promise.all(roots.splice(0).map((root) => rm(root, { recursive: true, force: true })));
});

describe("workspace files", () => {
  it("lists supported files while skipping dependency and oversized noise", async () => {
    const root = await workspace();
    await mkdir(join(root, "src"));
    await mkdir(join(root, "node_modules"));
    await writeFile(join(root, "src", "Main.kt"), "fun main() = Unit\n");
    await writeFile(join(root, "src", "view.wxml"), "<view />\n");
    await writeFile(join(root, "notes.bin"), Buffer.from([0, 1, 2]));
    await writeFile(join(root, "node_modules", "hidden.ts"), "hidden\n");

    const files = await listWorkspaceFiles(root);

    expect(files).toEqual([
      {
        relativePath: "notes.bin",
        name: "notes.bin",
        mimeType: "application/octet-stream",
        size: 3,
        attachable: false,
        isDirectory: false,
      },
      {
        relativePath: "src",
        name: "src",
        mimeType: "inode/directory",
        size: 0,
        attachable: false,
        isDirectory: true,
      },
      {
        relativePath: "src/Main.kt",
        name: "Main.kt",
        mimeType: "text/plain",
        size: 18,
        attachable: true,
        isDirectory: false,
      },
      {
        relativePath: "src/view.wxml",
        name: "view.wxml",
        mimeType: "text/plain",
        size: 9,
        attachable: true,
        isDirectory: false,
      },
    ]);
  });

  it("reads only supported files contained by the task workspace", async () => {
    const root = await workspace();
    const outside = await workspace();
    await writeFile(join(root, "README.md"), "# Demo\n");
    await writeFile(join(outside, "secret.txt"), "secret\n");

    await expect(readWorkspaceAttachment(root, "README.md")).resolves.toMatchObject({
      name: "README.md",
      mimeType: "text/markdown",
      body: Buffer.from("# Demo\n"),
    });
    await expect(readWorkspaceAttachment(root, "../secret.txt")).rejects.toThrow(
      "workspace-file-not-found",
    );
  });
});

async function workspace(): Promise<string> {
  const root = await mkdtemp(join(tmpdir(), "codex-remote-workspace-"));
  roots.push(root);
  return root;
}
