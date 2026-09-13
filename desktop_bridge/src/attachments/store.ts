import { randomUUID } from "node:crypto";
import {
  mkdir,
  readdir,
  readFile,
  rmdir,
  stat,
  unlink,
  writeFile,
} from "node:fs/promises";
import { dirname, extname, join } from "node:path";

import type { DesktopAttachment } from "../ipc/adapter.js";

export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
export const ATTACHMENT_TTL_MS = 60 * 60_000;

const IMAGE_TYPES = new Map<string, ReadonlySet<string>>([
  ["image/png", new Set([".png"])],
  ["image/jpeg", new Set([".jpg", ".jpeg"])],
  ["image/webp", new Set([".webp"])],
  ["image/gif", new Set([".gif"])],
]);

const FILE_TYPES = new Map<string, ReadonlySet<string>>([
  ["application/pdf", new Set([".pdf"])],
  ["text/markdown", new Set([".md", ".markdown"])],
  ["application/json", new Set([".json"])],
  ["text/csv", new Set([".csv"])],
  ["application/xml", new Set([".xml"])],
  ["text/xml", new Set([".xml"])],
  ["application/yaml", new Set([".yaml", ".yml"])],
  ["text/yaml", new Set([".yaml", ".yml"])],
  [
    "text/plain",
    new Set([
      ".txt", ".log", ".md", ".markdown", ".csv", ".xml", ".yaml", ".yml",
      ".py", ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".kt", ".kts",
      ".java", ".c", ".h", ".cc", ".cpp", ".hpp", ".rs", ".go", ".swift",
      ".sh", ".ps1", ".html", ".css", ".scss", ".sql", ".toml", ".ini",
      ".cfg", ".gradle", ".properties",
      ".wxml", ".wxss", ".jsonc", ".vue", ".svelte", ".astro",
      ".dart", ".rb", ".php", ".bat", ".cmd", ".psm1", ".fish",
      ".lock", ".gitignore", ".gitattributes", ".editorconfig",
    ]),
  ],
]);

export function supportedAttachmentMimeType(path: string): string | null {
  const extension = extname(path).toLowerCase();
  for (const [mimeType, extensions] of IMAGE_TYPES) {
    if (extensions.has(extension)) return mimeType;
  }
  for (const [mimeType, extensions] of FILE_TYPES) {
    if (extensions.has(extension)) return mimeType;
  }
  return null;
}

export type StoredAttachment = {
  attachmentId: string;
  ownerDeviceId: string;
  name: string;
  mimeType: string;
  size: number;
  kind: "image" | "file";
  expiresAt: number;
  fsPath: string;
};

export type PublicAttachment = Omit<StoredAttachment, "ownerDeviceId" | "fsPath">;

export class AttachmentStore {
  private readonly records = new Map<string, StoredAttachment>();

  static async open(
    root: string,
    options: {
      now?: () => number;
      idFactory?: () => string;
      ttlMs?: number;
    } = {},
  ): Promise<AttachmentStore> {
    const store = new AttachmentStore(
      root,
      options.now,
      options.idFactory,
      options.ttlMs,
    );
    await store.loadExisting();
    return store;
  }

  constructor(
    private readonly root: string,
    private readonly now: () => number = Date.now,
    private readonly idFactory: () => string = randomUUID,
    private readonly ttlMs = ATTACHMENT_TTL_MS,
  ) {}

  async save(
    ownerDeviceId: string,
    name: string,
    mimeType: string,
    body: Buffer,
  ): Promise<PublicAttachment> {
    const validated = validateUpload(name, mimeType, body);
    await this.pruneExpired();
    await mkdir(this.root, { recursive: true });
    const attachmentId = this.idFactory();
    if (!attachmentId || this.records.has(attachmentId)) {
      throw new Error("attachment-id-collision");
    }
    const attachmentRoot = join(this.root, attachmentId);
    const fsPath = join(attachmentRoot, validated.name);
    await mkdir(attachmentRoot);
    try {
      await writeFile(fsPath, body, { flag: "wx" });
    } catch (error) {
      await rmdir(attachmentRoot).catch(() => undefined);
      throw error;
    }
    const record: StoredAttachment = {
      attachmentId,
      ownerDeviceId,
      name: validated.name,
      mimeType: validated.mimeType,
      size: body.length,
      kind: validated.kind,
      expiresAt: this.now() + this.ttlMs,
      fsPath,
    };
    try {
      await writeFile(
        metadataPath(attachmentRoot),
        JSON.stringify(persistedAttachment(record)),
        { encoding: "utf8", flag: "wx" },
      );
    } catch (error) {
      await unlink(fsPath).catch(() => undefined);
      await rmdir(attachmentRoot).catch(() => undefined);
      throw error;
    }
    this.records.set(attachmentId, record);
    return publicAttachment(record);
  }

  async resolve(
    ownerDeviceId: string,
    attachmentIds: string[],
  ): Promise<DesktopAttachment[]> {
    await this.pruneExpired();
    const uniqueIds = [...new Set(attachmentIds)];
    if (uniqueIds.length !== attachmentIds.length) {
      throw new Error("duplicate-attachment-id");
    }
    const resolved: DesktopAttachment[] = [];
    for (const attachmentId of uniqueIds) {
      const record = this.records.get(attachmentId);
      if (!record || record.ownerDeviceId !== ownerDeviceId) {
        throw new Error("attachment-not-found");
      }
      try {
        const file = await stat(record.fsPath);
        if (!file.isFile() || file.size !== record.size) throw new Error("invalid");
      } catch {
        this.records.delete(attachmentId);
        throw new Error("attachment-not-found");
      }
      resolved.push({
        attachmentId: record.attachmentId,
        path: record.fsPath,
        fsPath: record.fsPath,
        label: record.name,
        kind: record.kind,
      });
    }
    return resolved;
  }

  async remove(ownerDeviceId: string, attachmentId: string): Promise<boolean> {
    await this.pruneExpired();
    const record = this.records.get(attachmentId);
    if (!record || record.ownerDeviceId !== ownerDeviceId) return false;
    this.records.delete(attachmentId);
    await removeManagedDirectory(dirname(record.fsPath));
    return true;
  }

  async pruneExpired(): Promise<void> {
    const now = this.now();
    const expired = [...this.records.values()].filter(
      (record) => record.expiresAt <= now,
    );
    await Promise.all(
      expired.map(async (record) => {
        this.records.delete(record.attachmentId);
        await removeManagedDirectory(dirname(record.fsPath));
      }),
    );
  }

  private async loadExisting(): Promise<void> {
    await mkdir(this.root, { recursive: true });
    const entries = await readdir(this.root, { withFileTypes: true });
    for (const entry of entries) {
      if (!entry.isDirectory() || !isSafeStorageId(entry.name)) continue;
      const attachmentRoot = join(this.root, entry.name);
      let record: StoredAttachment | null = null;
      try {
        const raw = JSON.parse(
          await readFile(metadataPath(attachmentRoot), "utf8"),
        ) as unknown;
        record = parsePersistedAttachment(entry.name, attachmentRoot, raw);
        if (!record || record.expiresAt <= this.now()) throw new Error("expired");
        const file = await stat(record.fsPath);
        if (!file.isFile() || file.size !== record.size) throw new Error("invalid");
      } catch {
        await removeManagedDirectory(attachmentRoot);
        continue;
      }
      this.records.set(record.attachmentId, record);
    }
  }
}

function validateUpload(
  rawName: string,
  rawMimeType: string,
  body: Buffer,
): { name: string; mimeType: string; extension: string; kind: "image" | "file" } {
  const name = rawName.trim();
  if (!isValidAttachmentName(name)) {
    throw new Error("invalid-attachment-name");
  }
  if (body.length === 0) throw new Error("attachment-empty");
  if (body.length > MAX_ATTACHMENT_BYTES) throw new Error("attachment-too-large");
  const mimeType = rawMimeType.split(";", 1)[0]?.trim().toLowerCase() ?? "";
  const imageExtensions = IMAGE_TYPES.get(mimeType);
  const fileExtensions = FILE_TYPES.get(mimeType);
  const extensions = imageExtensions ?? fileExtensions;
  if (!extensions) throw new Error("unsupported-attachment-media-type");
  const extension = extname(name).toLowerCase();
  if (!extensions.has(extension)) throw new Error("attachment-type-mismatch");
  if (!matchesContent(mimeType, body)) throw new Error("attachment-content-mismatch");
  return {
    name,
    mimeType,
    extension,
    kind: imageExtensions ? "image" : "file",
  };
}

function matchesContent(mimeType: string, body: Buffer): boolean {
  if (mimeType === "image/png") {
    return body.length >= 8 && body.subarray(0, 8).equals(
      Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    );
  }
  if (mimeType === "image/jpeg") {
    return body.length >= 3 && body[0] === 0xff && body[1] === 0xd8 && body[2] === 0xff;
  }
  if (mimeType === "image/gif") {
    const signature = body.subarray(0, 6).toString("ascii");
    return signature === "GIF87a" || signature === "GIF89a";
  }
  if (mimeType === "image/webp") {
    return (
      body.length >= 12 &&
      body.subarray(0, 4).toString("ascii") === "RIFF" &&
      body.subarray(8, 12).toString("ascii") === "WEBP"
    );
  }
  if (mimeType === "application/pdf") {
    return body.length >= 5 && body.subarray(0, 5).toString("ascii") === "%PDF-";
  }
  if (FILE_TYPES.has(mimeType)) {
    if (body.includes(0)) return false;
    try {
      new TextDecoder("utf-8", { fatal: true }).decode(body);
      return true;
    } catch {
      return false;
    }
  }
  return false;
}

function publicAttachment(record: StoredAttachment): PublicAttachment {
  const { ownerDeviceId: _ownerDeviceId, fsPath: _fsPath, ...publicRecord } = record;
  return structuredClone(publicRecord);
}

function persistedAttachment(
  record: StoredAttachment,
): Omit<StoredAttachment, "fsPath"> {
  const { fsPath: _fsPath, ...persisted } = record;
  return persisted;
}

function parsePersistedAttachment(
  attachmentId: string,
  attachmentRoot: string,
  value: unknown,
): StoredAttachment | null {
  if (!isRecord(value)) return null;
  const name = typeof value.name === "string" ? value.name : "";
  const mimeType = typeof value.mimeType === "string" ? value.mimeType : "";
  const expectedKind = IMAGE_TYPES.has(mimeType) ? "image" : FILE_TYPES.has(mimeType) ? "file" : null;
  if (
    value.attachmentId !== attachmentId ||
    typeof value.ownerDeviceId !== "string" ||
    value.ownerDeviceId.length === 0 ||
    !isValidAttachmentName(name) ||
    !expectedKind ||
    value.kind !== expectedKind ||
    !Number.isSafeInteger(value.size) ||
    (value.size as number) <= 0 ||
    (value.size as number) > MAX_ATTACHMENT_BYTES ||
    !Number.isSafeInteger(value.expiresAt)
  ) {
    return null;
  }
  return {
    attachmentId,
    ownerDeviceId: value.ownerDeviceId,
    name,
    mimeType,
    size: value.size as number,
    kind: expectedKind,
    expiresAt: value.expiresAt as number,
    fsPath: join(attachmentRoot, name),
  };
}

function isValidAttachmentName(name: string): boolean {
  return Boolean(
    name &&
    name.length <= 128 &&
    !name.includes("..") &&
    !/[\\/<>:"|?*\u0000-\u001F\u007F]/.test(name) &&
    !/[ .]$/.test(name) &&
    !/^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(name),
  );
}

function isSafeStorageId(value: string): boolean {
  return /^[A-Za-z0-9_-]{1,80}$/.test(value);
}

function metadataPath(attachmentRoot: string): string {
  return join(attachmentRoot, ".metadata.json");
}

async function removeManagedDirectory(attachmentRoot: string): Promise<void> {
  let entries;
  try {
    entries = await readdir(attachmentRoot, { withFileTypes: true });
  } catch {
    return;
  }
  await Promise.all(
    entries
      .filter((entry) => entry.isFile() || entry.isSymbolicLink())
      .map((entry) => unlink(join(attachmentRoot, entry.name)).catch(() => undefined)),
  );
  await rmdir(attachmentRoot).catch(() => undefined);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
