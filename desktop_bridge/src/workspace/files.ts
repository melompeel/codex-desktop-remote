import { readdir, readFile, realpath, stat } from "node:fs/promises";
import { basename, isAbsolute, relative, resolve, sep } from "node:path";

import {
  MAX_ATTACHMENT_BYTES,
  supportedAttachmentMimeType,
} from "../attachments/store.js";

export type WorkspaceFile = {
  relativePath: string;
  name: string;
  mimeType: string;
  size: number;
  attachable: boolean;
  isDirectory: boolean;
};

const SKIPPED_DIRECTORIES = new Set([
  ".git",
  ".gradle",
  ".idea",
  ".next",
  ".venv",
  "build",
  "dist",
  "node_modules",
  "target",
]);

export async function listWorkspaceFiles(
  workspaceRoot: string,
  query = "",
  limit = 150,
): Promise<WorkspaceFile[]> {
  const root = await realpath(workspaceRoot);
  const needle = query.trim().toLowerCase();
  const pending = [root];
  const files: WorkspaceFile[] = [];
  let visitedDirectories = 0;
  while (pending.length > 0 && files.length < limit && visitedDirectories < 2_000) {
    const directory = pending.shift()!;
    visitedDirectories += 1;
    const entries = await readdir(directory, { withFileTypes: true }).catch(() => []);
    entries.sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      if (files.length >= limit) break;
      const absolutePath = resolve(directory, entry.name);
      if (entry.isSymbolicLink()) continue;
      if (entry.isDirectory()) {
        if (SKIPPED_DIRECTORIES.has(entry.name.toLowerCase())) continue;
        const relativePath = relative(root, absolutePath).split(sep).join("/");
        if (!needle || relativePath.toLowerCase().includes(needle)) {
          files.push({
            relativePath,
            name: entry.name,
            mimeType: "inode/directory",
            size: 0,
            attachable: false,
            isDirectory: true,
          });
        }
        pending.push(absolutePath);
        continue;
      }
      if (!entry.isFile()) continue;
      const mimeType = supportedAttachmentMimeType(entry.name);
      const relativePath = relative(root, absolutePath).split(sep).join("/");
      if (needle && !relativePath.toLowerCase().includes(needle)) continue;
      const metadata = await stat(absolutePath).catch(() => null);
      if (!metadata) continue;
      const attachable = Boolean(mimeType) && metadata.size > 0 && metadata.size <= MAX_ATTACHMENT_BYTES;
      files.push({
        relativePath,
        name: entry.name,
        mimeType: mimeType ?? "application/octet-stream",
        size: metadata.size,
        attachable,
        isDirectory: false,
      });
    }
  }
  return files;
}

export async function readWorkspaceAttachment(
  workspaceRoot: string,
  relativePath: string,
): Promise<{ name: string; mimeType: string; body: Buffer }> {
  if (!relativePath || relativePath.includes("\0") || isAbsolute(relativePath)) {
    throw new Error("invalid-workspace-file-path");
  }
  const root = await realpath(workspaceRoot);
  const requested = resolve(root, relativePath);
  const resolved = await realpath(requested).catch(() => null);
  if (!resolved || !isWithin(root, resolved)) throw new Error("workspace-file-not-found");
  const metadata = await stat(resolved);
  if (!metadata.isFile()) throw new Error("workspace-file-not-found");
  if (metadata.size > MAX_ATTACHMENT_BYTES) throw new Error("attachment-too-large");
  const mimeType = supportedAttachmentMimeType(resolved);
  if (!mimeType) throw new Error("unsupported-attachment-media-type");
  return { name: basename(resolved), mimeType, body: await readFile(resolved) };
}

function isWithin(root: string, target: string): boolean {
  const path = relative(root, target);
  return path === "" || (!path.startsWith(`..${sep}`) && path !== ".." && !isAbsolute(path));
}
