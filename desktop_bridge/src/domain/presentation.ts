import { createHash } from "node:crypto";
import { basename, extname, isAbsolute } from "node:path";
import { fileURLToPath } from "node:url";

import type { ThreadStream } from "./store.js";

export type TimelineMedia = {
  mediaId: string;
  name: string;
  mimeType: string;
};

export type TimelineResource = {
  resourceId: string;
  name: string;
  mimeType: string;
};

export type TimelineItem = {
  id: string;
  turnId: string;
  sourceItemId?: string;
  turnDurationMs?: number;
  kind: "user" | "userImage" | "assistant" | "command" | "file" | "plan" | "status" | "image";
  text: string;
  status?: string;
  media?: TimelineMedia;
  resources?: TimelineResource[];
};

export type ThreadMediaFile = TimelineMedia & { fsPath: string };
export type ThreadResourceFile = TimelineResource & { fsPath: string };

export type TaskDetail = {
  threadId: string;
  title: string;
  status: string;
  revision: number;
  items: TimelineItem[];
  hasMoreHistory: boolean;
  historyCursor?: string;
  cwd?: string;
  cwdGroupKey?: string;
  cwdGroupLabel?: string;
  gitInfo?: GitInfoSummary;
  settings?: ThreadSettingsSummary;
  activeTurnId?: string;
};

export type TimelinePageOptions = {
  limit?: number;
  cursor?: string;
};

export type GitInfoSummary = {
  branch?: string;
  repositoryRoot?: string;
  sha?: string;
  isDirty?: boolean;
};

export type ThreadSettingsSummary = {
  model?: string;
  effort?: string;
  serviceTier?: string;
};

export type ThreadPresentationMetadata = {
  cwd?: string;
  cwdGroupKey?: string;
  cwdGroupLabel?: string;
  gitInfo?: GitInfoSummary;
  settings?: ThreadSettingsSummary;
  activeTurnId?: string;
};

export type TaskDiff = {
  threadId: string;
  revision: number;
  turns: Array<{
    turnId: string;
    status: string;
    unifiedDiff: string;
  }>;
  files: Array<{
    turnId: string;
    itemId: string;
    path: string;
    kind: string;
    unifiedDiff: string;
  }>;
};

const MAX_ITEM_TEXT = 4_000;
const MAX_ITEMS = 200;

export function presentThread(
  thread: ThreadStream,
  options: TimelinePageOptions = {},
): TaskDetail {
  const turns = orderedTurns(thread.state);
  const items: TimelineItem[] = [];
  for (let turnIndex = 0; turnIndex < turns.length; turnIndex += 1) {
    const turn = asRecord(turns[turnIndex]);
    if (!turn) continue;
    const turnId = readString(turn.id) || readString(turn.turnId) || `turn-${turnIndex}`;
    const turnStatus = readStatus(turn.status);
    const turnDurationMs = readTurnDurationMs(turn);
    const rawItems = Array.isArray(turn.items) ? turn.items : [];
    for (let itemIndex = 0; itemIndex < rawItems.length; itemIndex += 1) {
      const item = asRecord(rawItems[itemIndex]);
      if (!item) continue;
      const sourceItemId = readString(item.id) || `${turnId}-${itemIndex}`;
      items.push(...presentItems(
        thread.threadId,
        item,
        turnId,
        itemIndex,
        turnStatus,
      ).map((presented) => ({
        ...presented,
        sourceItemId,
        ...(turnDurationMs !== undefined ? { turnDurationMs } : {}),
      })));
    }
  }
  const page = pageTimelineItems(items, options);
  return {
    threadId: thread.threadId,
    title: readString(thread.state.title) || readString(thread.state.name) || "Untitled task",
    status: threadStatus(thread.state),
    revision: thread.revision,
    items: page.items,
    hasMoreHistory: page.hasMoreHistory,
    ...(page.historyCursor ? { historyCursor: page.historyCursor } : {}),
    ...presentThreadMetadata(thread.state),
  };
}

function pageTimelineItems(
  items: TimelineItem[],
  options: TimelinePageOptions,
): {
  items: TimelineItem[];
  hasMoreHistory: boolean;
  historyCursor?: string;
} {
  const limit = options.limit ?? MAX_ITEMS;
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_ITEMS) {
    throw new Error("invalid-history-limit");
  }
  const end = options.cursor
    ? findTimelineCursor(items, options.cursor)
    : items.length;
  if (end === 0) return { items: [], hasMoreHistory: false };

  const initialStart = Math.max(0, end - limit);
  const userIndex = findLatestUserIndex(items, end);
  const userGroupIndexes = userIndex >= 0
    ? latestUserGroupIndexes(items, userIndex, end)
    : [];
  const anchorIndexes = userGroupIndexes.some((index) => index < initialStart)
    ? userGroupIndexes.slice(0, Math.max(1, Math.floor(limit / 3)))
    : [];
  const sliceBudget = Math.max(0, limit - anchorIndexes.length);
  const start = Math.max(0, end - sliceBudget);
  const anchors = anchorIndexes
    .filter((index) => index < start)
    .map((index) => items[index]!)
    .slice(-(limit - Math.min(limit, end - start)));
  const page = [...anchors, ...items.slice(start, end)];
  const historyCursor = start > 0 ? encodeTimelineCursor(items[start]!) : undefined;
  return {
    items: page,
    hasMoreHistory: start > 0,
    ...(historyCursor ? { historyCursor } : {}),
  };
}

function findLatestUserIndex(items: TimelineItem[], end: number): number {
  for (let index = end - 1; index >= 0; index -= 1) {
    const kind = items[index]?.kind;
    if (kind === "user" || kind === "userImage") return index;
  }
  return -1;
}

function latestUserGroupIndexes(
  items: TimelineItem[],
  userIndex: number,
  end: number,
): number[] {
  const anchor = items[userIndex]!;
  const sourceItemId = anchor.sourceItemId ?? anchor.id;
  const result: number[] = [];
  for (let index = 0; index < end; index += 1) {
    const item = items[index]!;
    if (
      item.turnId === anchor.turnId &&
      (item.sourceItemId ?? item.id) === sourceItemId &&
      (item.kind === "user" || item.kind === "userImage")
    ) result.push(index);
  }
  return result;
}

function encodeTimelineCursor(item: TimelineItem): string {
  return Buffer.from(JSON.stringify([item.turnId, item.id]), "utf8").toString("base64url");
}

function findTimelineCursor(items: TimelineItem[], cursor: string): number {
  let key: unknown;
  try {
    key = JSON.parse(Buffer.from(cursor, "base64url").toString("utf8"));
  } catch {
    throw new Error("invalid-history-cursor");
  }
  if (
    !Array.isArray(key) ||
    key.length !== 2 ||
    typeof key[0] !== "string" ||
    typeof key[1] !== "string"
  ) throw new Error("invalid-history-cursor");
  const index = items.findIndex((item) => item.turnId === key[0] && item.id === key[1]);
  if (index < 0) throw new Error("history-cursor-expired");
  return index;
}

function readTurnDurationMs(turn: Record<string, unknown>): number | undefined {
  const direct = readNonNegativeNumber(turn.durationMs);
  if (direct !== undefined) return direct;
  const startedAt = readNonNegativeNumber(turn.turnStartedAtMs ?? turn.startedAtMs);
  const completedAt = readNonNegativeNumber(turn.turnCompletedAtMs ?? turn.completedAtMs);
  if (startedAt === undefined || completedAt === undefined || completedAt < startedAt) {
    return undefined;
  }
  return completedAt - startedAt;
}

function readNonNegativeNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value >= 0
    ? value
    : undefined;
}

export function presentThreadMetadata(
  state: Record<string, unknown>,
): ThreadPresentationMetadata {
  const cwd = extractCwd(state);
  const gitInfo = extractGitInfo(state);
  const settings = extractThreadSettings(state);
  const activeTurnId = extractActiveTurnId(state);
  return {
    ...(cwd
      ? {
          cwd,
          cwdGroupKey: normalizeCwdGroupKey(cwd),
          cwdGroupLabel: cwdLabel(cwd),
        }
      : {}),
    ...(gitInfo ? { gitInfo } : {}),
    ...(settings ? { settings } : {}),
    ...(activeTurnId ? { activeTurnId } : {}),
  };
}

export function presentThreadDiff(thread: ThreadStream): TaskDiff {
  const turns = orderedTurns(thread.state);
  const result: TaskDiff = {
    threadId: thread.threadId,
    revision: thread.revision,
    turns: [],
    files: [],
  };
  for (let turnIndex = 0; turnIndex < turns.length; turnIndex += 1) {
    const turn = asRecord(turns[turnIndex]);
    if (!turn) continue;
    const turnId = readString(turn.id) || readString(turn.turnId) || `turn-${turnIndex}`;
    const status = readStatus(turn.status);
    const turnDiff = readUnifiedDiff(
      turn.unifiedDiff ?? turn.diff ?? asRecord(turn.output)?.unifiedDiff,
    );
    if (turnDiff) result.turns.push({ turnId, status, unifiedDiff: turnDiff });
    const items = Array.isArray(turn.items) ? turn.items : [];
    for (let itemIndex = 0; itemIndex < items.length; itemIndex += 1) {
      const item = asRecord(items[itemIndex]);
      if (!item || !isFileChangeType(readString(item.type))) continue;
      const itemId = readString(item.id) || `${turnId}-${itemIndex}`;
      const changes = Array.isArray(item.changes) ? item.changes : [item];
      for (const rawChange of changes) {
        const change = asRecord(rawChange);
        if (!change) continue;
        const unifiedDiff = readUnifiedDiff(
          change.unifiedDiff ?? change.diff ?? change.patch,
        );
        if (!unifiedDiff) continue;
        result.files.push({
          turnId,
          itemId,
          path: readString(change.path) || readString(change.filePath),
          kind: readString(change.kind) || readString(change.type) || "update",
          unifiedDiff,
        });
      }
    }
  }
  return result;
}

function orderedTurns(state: Record<string, unknown>): unknown[] {
  const direct = Array.isArray(state.turns) ? state.turns : [];
  if (direct.length > 0) return direct;
  const turnHistory = asRecord(state.turnHistory);
  const history = asRecord(turnHistory?.history);
  const entities = asRecord(history?.entitiesByKey);
  const islands = Array.isArray(history?.islands) ? history.islands : [];
  if (!entities || islands.length === 0) return [];
  const turns: unknown[] = [];
  const seen = new Set<string>();
  for (const islandValue of islands) {
    const island = asRecord(islandValue);
    const entries = Array.isArray(island?.entries) ? island.entries : [];
    for (const entryValue of entries) {
      const entry = asRecord(entryValue);
      const key = readString(entry?.value) || readString(entry?.key);
      if (!key || seen.has(key) || !asRecord(entities[key])) continue;
      seen.add(key);
      turns.push(entities[key]);
    }
  }
  return turns;
}

function presentItems(
  threadId: string,
  item: Record<string, unknown>,
  turnId: string,
  index: number,
  turnStatus: string,
): TimelineItem[] {
  const rawType = readString(item.type);
  const type = rawType.toLowerCase().replace(/[-_]/g, "");
  const id = readString(item.id) || `${turnId}-${index}`;
  const status = readStatus(item.status) || turnStatus;
  if (type === "imageview" || type === "image") {
    const media = imageMedia(threadId, id, item);
    return singleTimeline(
      media
        ? timeline(id, turnId, "image", media.name, status, publicMedia(media))
        : null,
    );
  }
  if (type === "usermessage" || type === "user" || type === "steeringusermessage") {
    return presentUserItems(threadId, id, turnId, item.content ?? item.input ?? item.text, status);
  }
  if (type === "agentmessage" || type === "assistantmessage" || type === "assistant") {
    return presentRichTextItems(
      threadId,
      id,
      turnId,
      "assistant",
      readText(item.text ?? item.content),
      status,
    );
  }
  if (type.includes("plan")) {
    return presentRichTextItems(
      threadId,
      id,
      turnId,
      "plan",
      readText(item.text ?? item.explanation ?? item.plan ?? item.steps),
      status,
    );
  }
  if (type === "commandexecution" || type === "command") {
    const command = sanitizeTerminalText(
      readString(item.command) || readString(item.cmd) || readString(item.commandLine),
    );
    const output = sanitizeTerminalText(readText(
      item.aggregatedOutput ?? item.aggregated_output ?? item.output ?? item.stderr ?? item.stdout,
    ));
    return singleTimeline(
      timeline(
        id,
        turnId,
        "command",
        [command ? `$ ${command}` : "Command", output].filter(Boolean).join("\n"),
        status,
      ),
    );
  }
  if (isFileChangeType(rawType)) {
    const changes = Array.isArray(item.changes) ? item.changes : [item];
    const paths = changes
      .map((entry) => asRecord(entry))
      .map((entry) => readString(entry?.path) || readString(entry?.filePath))
      .filter(Boolean);
    return singleTimeline(
      timeline(id, turnId, "file", paths.length ? paths.join("\n") : "Files changed", status),
    );
  }
  if (type === "reasoning") {
    return singleTimeline(
      timeline(id, turnId, "status", readText(item.summary ?? item.content ?? item.text), status),
    );
  }
  return [];
}

function presentUserItems(
  threadId: string,
  itemId: string,
  turnId: string,
  content: unknown,
  status: string,
): TimelineItem[] {
  const presented: TimelineItem[] = [];
  for (const [index, part] of userMessageParts(threadId, itemId, content).entries()) {
    const id = `${itemId}:${"media" in part ? "image" : "text"}:${index}`;
    const item = "media" in part
      ? timeline(id, turnId, "userImage", part.media.name, status, publicMedia(part.media))
      : timeline(id, turnId, "user", part.text, status);
    if (item) presented.push(item);
  }
  return presented;
}

function userMessageParts(
  threadId: string,
  itemId: string,
  content: unknown,
): RichTextPart[] {
  const values = Array.isArray(content) ? content : [content];
  const result: RichTextPart[] = [];
  const text = mutableTextPart(result);
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    const record = asRecord(value);
    const type = readString(record?.type).toLowerCase().replace(/[-_]/g, "");
    if (record && (type === "localimage" || type === "image" || type === "imageview")) {
      const media = imageMedia(threadId, `${itemId}:user-image:${index}`, record);
      if (media) result.push({ media });
      continue;
    }
    const valueText = readText(value);
    if (valueText) text(valueText);
  }
  return result;
}

function mutableTextPart(result: RichTextPart[]): (value: string) => void {
  return (value: string) => {
    const previous = result.at(-1);
    if (previous && "text" in previous) previous.text = `${previous.text}\n${value}`;
    else result.push({ text: value });
  };
}

function presentRichTextItems(
  threadId: string,
  itemId: string,
  turnId: string,
  kind: "assistant" | "plan",
  text: string,
  status: string,
): TimelineItem[] {
  const parts = splitLocalMarkdownImages(threadId, itemId, text);
  if (!parts.some((part) => "media" in part)) {
    const linked = linkedText(threadId, itemId, text);
    return singleTimeline(
      timeline(itemId, turnId, kind, linked.text, status, undefined, linked.resources),
    );
  }

  const presented: TimelineItem[] = [];
  for (let index = 0; index < parts.length; index += 1) {
    const part = parts[index]!;
    const presentationId = `${itemId}:${"media" in part ? "image" : "text"}:${index}`;
    if ("media" in part) {
      const image = timeline(
        presentationId,
        turnId,
        "image",
        part.media.name,
        status,
        publicMedia(part.media),
      );
      if (image) presented.push(image);
      continue;
    }
    const linked = linkedText(threadId, itemId, part.text);
    const message = timeline(
      presentationId,
      turnId,
      kind,
      linked.text,
      status,
      undefined,
      linked.resources,
    );
    if (message) presented.push(message);
  }
  return presented;
}

function singleTimeline(item: TimelineItem | null): TimelineItem[] {
  return item ? [item] : [];
}

function publicMedia(media: ThreadMediaFile): TimelineMedia {
  return {
    mediaId: media.mediaId,
    name: media.name,
    mimeType: media.mimeType,
  };
}

export function sanitizeTerminalText(value: string): string {
  return stripTerminalControls(
    value.replace(/\r\n/g, "\n").replace(/\r/g, "\n"),
  ).trim();
}

function stripTerminalControls(value: string): string {
  let output = "";
  let index = 0;
  while (index < value.length) {
    const code = value.charCodeAt(index);
    if (code === 0x1b) {
      const next = value.charCodeAt(index + 1);
      if (next === 0x5b) index = consumeCsi(value, index + 2);
      else if (next === 0x5d) index = consumeControlString(value, index + 2, true);
      else if (next === 0x50 || next === 0x58 || next === 0x5e || next === 0x5f) {
        index = consumeControlString(value, index + 2, false);
      } else {
        index = consumeEscapeSequence(value, index + 1);
      }
      continue;
    }
    if (code === 0x9b) {
      index = consumeCsi(value, index + 1);
      continue;
    }
    if (code === 0x9d) {
      index = consumeControlString(value, index + 1, true);
      continue;
    }
    if (code === 0x90 || code === 0x98 || code === 0x9e || code === 0x9f) {
      index = consumeControlString(value, index + 1, false);
      continue;
    }
    if (
      (code >= 0x00 && code <= 0x08) ||
      (code >= 0x0b && code <= 0x1f) ||
      (code >= 0x7f && code <= 0x9f)
    ) {
      index += 1;
      continue;
    }
    output += value[index];
    index += 1;
  }
  return output;
}

function consumeCsi(value: string, start: number): number {
  let index = start;
  while (index < value.length) {
    const code = value.charCodeAt(index);
    index += 1;
    if (code >= 0x40 && code <= 0x7e) return index;
  }
  return value.length;
}

function consumeControlString(
  value: string,
  start: number,
  allowBell: boolean,
): number {
  let index = start;
  while (index < value.length) {
    const code = value.charCodeAt(index);
    if (allowBell && code === 0x07) return index + 1;
    if (code === 0x9c) return index + 1;
    if (code === 0x1b && value.charCodeAt(index + 1) === 0x5c) {
      return index + 2;
    }
    index += 1;
  }
  return value.length;
}

function consumeEscapeSequence(value: string, start: number): number {
  let index = start;
  while (index < value.length) {
    const code = value.charCodeAt(index);
    index += 1;
    if (code >= 0x30 && code <= 0x7e) return index;
    if (code < 0x20 || code > 0x2f) return index;
  }
  return value.length;
}

function timeline(
  id: string,
  turnId: string,
  kind: TimelineItem["kind"],
  text: string,
  status: string,
  media?: TimelineMedia,
  resources?: TimelineResource[],
): TimelineItem | null {
  const trimmed = text.trim();
  if (!trimmed) return null;
  return {
    id,
    turnId,
    kind,
    text: trimmed.slice(-MAX_ITEM_TEXT),
    ...(status ? { status } : {}),
    ...(media ? { media } : {}),
    ...(resources?.length ? { resources } : {}),
  };
}

type RichTextPart =
  | { text: string }
  | { media: ThreadMediaFile };

function splitLocalMarkdownImages(
  threadId: string,
  itemId: string,
  text: string,
): RichTextPart[] {
  const parts: RichTextPart[] = [];
  let cursor = 0;
  let imageIndex = 0;
  for (const match of text.matchAll(markdownLinkPattern())) {
    if (match[1] !== "!") continue;
    const matchIndex = match.index;
    const rawTarget = match[3];
    if (matchIndex === undefined || !rawTarget) continue;
    const media = markdownImageMedia(
      threadId,
      `${itemId}:markdown-image:${imageIndex}`,
      rawTarget,
    );
    if (!media) continue;
    if (matchIndex > cursor) parts.push({ text: text.slice(cursor, matchIndex) });
    parts.push({ media });
    cursor = matchIndex + match[0].length;
    imageIndex += 1;
  }
  if (parts.length === 0) return [{ text }];
  if (cursor < text.length) parts.push({ text: text.slice(cursor) });
  return parts;
}

export function resolveThreadMedia(
  thread: ThreadStream,
  mediaId: string,
): ThreadMediaFile | null {
  for (const turnValue of orderedTurns(thread.state)) {
    const turn = asRecord(turnValue);
    const turnId = readString(turn?.id) || readString(turn?.turnId) || "turn";
    const items = Array.isArray(turn?.items) ? turn.items : [];
    for (let index = 0; index < items.length; index += 1) {
      const item = asRecord(items[index]);
      if (!item) continue;
      const type = readString(item.type).toLowerCase().replace(/[-_]/g, "");
      const itemId = readString(item.id) || `${turnId}-${index}`;
      if (type === "imageview" || type === "image") {
        const media = imageMedia(thread.threadId, itemId, item);
        if (media?.mediaId === mediaId) return media;
        continue;
      }
      if (type === "usermessage" || type === "user" || type === "steeringusermessage") {
        for (const part of userMessageParts(
          thread.threadId,
          itemId,
          item.content ?? item.input ?? item.text,
        )) {
          if ("media" in part && part.media.mediaId === mediaId) return part.media;
        }
        continue;
      }
      if (!isAssistantOutputType(type)) continue;
      const text = assistantOutputText(type, item);
      for (const part of splitLocalMarkdownImages(thread.threadId, itemId, text)) {
        if ("media" in part && part.media.mediaId === mediaId) return part.media;
      }
    }
  }
  return null;
}

export function resolveThreadResource(
  thread: ThreadStream,
  resourceId: string,
): ThreadResourceFile | null {
  for (const turnValue of orderedTurns(thread.state)) {
    const turn = asRecord(turnValue);
    const turnId = readString(turn?.id) || readString(turn?.turnId) || "turn";
    const items = Array.isArray(turn?.items) ? turn.items : [];
    for (let index = 0; index < items.length; index += 1) {
      const item = asRecord(items[index]);
      if (!item) continue;
      const type = readString(item.type).toLowerCase().replace(/[-_]/g, "");
      if (!isAssistantOutputType(type)) continue;
      const itemId = readString(item.id) || `${turnId}-${index}`;
      const text = assistantOutputText(type, item);
      for (const resource of linkedText(thread.threadId, itemId, text).resourceFiles) {
        if (resource.resourceId === resourceId) return resource;
      }
    }
  }
  return null;
}

function imageMedia(
  threadId: string,
  itemId: string,
  item: Record<string, unknown>,
): ThreadMediaFile | null {
  const rawPath = readString(item.path) || readString(item.uri) || readString(item.url);
  if (!rawPath) return null;
  let fsPath: string;
  try {
    fsPath = rawPath.startsWith("file:") ? fileURLToPath(rawPath) : rawPath;
  } catch {
    return null;
  }
  if (!isAbsolute(fsPath) || isNetworkOrDevicePath(fsPath)) return null;
  const mimeType = imageMimeType(fsPath);
  if (!mimeType) return null;
  const name = basename(fsPath);
  const mediaId = createHash("sha256")
    .update(`${threadId}\0${itemId}\0${fsPath}`)
    .digest("hex")
    .slice(0, 32);
  return { mediaId, name, mimeType, fsPath };
}

function markdownImageMedia(
  threadId: string,
  sourceId: string,
  rawTarget: string,
): ThreadMediaFile | null {
  const fsPath = localMarkdownPath(rawTarget, false);
  if (!fsPath) return null;
  const mimeType = imageMimeType(fsPath);
  if (!mimeType) return null;
  const name = basename(fsPath);
  const mediaId = createHash("sha256")
    .update(`${threadId}\0${sourceId}\0${fsPath}`)
    .digest("hex")
    .slice(0, 32);
  return { mediaId, name, mimeType, fsPath };
}

function imageMimeType(path: string): string | null {
  switch (extname(path).toLowerCase()) {
    case ".png": return "image/png";
    case ".jpg":
    case ".jpeg": return "image/jpeg";
    case ".webp": return "image/webp";
    case ".gif": return "image/gif";
    default: return null;
  }
}

function linkedText(
  threadId: string,
  itemId: string,
  text: string,
): { text: string; resources: TimelineResource[]; resourceFiles: ThreadResourceFile[] } {
  const resources: TimelineResource[] = [];
  const resourceFiles: ThreadResourceFile[] = [];
  const rewritten = text.replace(
    markdownLinkPattern(),
    (match: string, imageMarker: string, label: string, rawTarget: string) => {
      if (imageMarker) return match;
      const resource = localLinkedResource(threadId, itemId, rawTarget);
      if (!resource) return match;
      resources.push(publicResource(resource));
      resourceFiles.push(resource);
      return `[${label}](codexremote://resource/${resource.resourceId})`;
    },
  );
  return { text: rewritten, resources, resourceFiles };
}

function publicResource(resource: ThreadResourceFile): TimelineResource {
  return {
    resourceId: resource.resourceId,
    name: resource.name,
    mimeType: resource.mimeType,
  };
}

function localLinkedResource(
  threadId: string,
  itemId: string,
  rawTarget: string,
): ThreadResourceFile | null {
  const fsPath = localMarkdownPath(rawTarget, true);
  if (!fsPath) return null;
  const name = basename(fsPath);
  const mimeType = resourceMimeType(fsPath);
  const resourceId = createHash("sha256")
    .update(`${threadId}\0${itemId}\0${fsPath}`)
    .digest("hex")
    .slice(0, 32);
  return { resourceId, name, mimeType, fsPath };
}

function localMarkdownPath(rawTarget: string, stripLineSuffix: boolean): string | null {
  let target = rawTarget.startsWith("<") && rawTarget.endsWith(">")
    ? rawTarget.slice(1, -1)
    : rawTarget;
  try {
    target = decodeURI(target);
  } catch {
    return null;
  }
  if (/^[a-z][a-z0-9+.-]*:/i.test(target) && !target.startsWith("file:")) {
    if (!/^[a-z]:[\\/]/i.test(target)) return null;
  }
  let fsPath: string;
  try {
    fsPath = target.startsWith("file:") ? fileURLToPath(target) : target;
  } catch {
    return null;
  }
  if (stripLineSuffix) {
    const lineSuffix = fsPath.match(/^(.*):\d+(?::\d+)?$/);
    if (lineSuffix && isAbsolute(lineSuffix[1]!)) fsPath = lineSuffix[1]!;
  }
  if (/^\/[a-z]:\//i.test(fsPath)) fsPath = fsPath.slice(1);
  if (!isAbsolute(fsPath) || isNetworkOrDevicePath(fsPath)) return null;
  return fsPath;
}

function isNetworkOrDevicePath(path: string): boolean {
  return path.replace(/\//g, "\\").startsWith("\\\\");
}

function markdownLinkPattern(): RegExp {
  return /(!?)\[([^\]]*)\]\(\s*(<[^>]+>|[^)\s]+)(?:\s+"[^"]*")?\s*\)/g;
}

function isAssistantOutputType(type: string): boolean {
  return type === "agentmessage" ||
    type === "assistantmessage" ||
    type === "assistant" ||
    type.includes("plan");
}

function assistantOutputText(
  type: string,
  item: Record<string, unknown>,
): string {
  return type.includes("plan")
    ? readText(item.text ?? item.explanation ?? item.plan ?? item.steps)
    : readText(item.text ?? item.content);
}

function resourceMimeType(path: string): string {
  switch (extname(path).toLowerCase()) {
    case ".png": return "image/png";
    case ".jpg":
    case ".jpeg": return "image/jpeg";
    case ".webp": return "image/webp";
    case ".gif": return "image/gif";
    case ".pdf": return "application/pdf";
    case ".md":
    case ".markdown": return "text/markdown";
    case ".json": return "application/json";
    case ".csv": return "text/csv";
    case ".xml": return "application/xml";
    case ".yaml":
    case ".yml": return "application/yaml";
    case ".txt":
    case ".log":
    case ".ts":
    case ".tsx":
    case ".js":
    case ".jsx":
    case ".kt":
    case ".kts":
    case ".java":
    case ".py":
    case ".rs":
    case ".go":
    case ".c":
    case ".h":
    case ".cpp":
    case ".hpp":
    case ".css":
    case ".html":
    case ".sh":
    case ".ps1": return "text/plain";
    default: return "application/octet-stream";
  }
}

function readText(value: unknown): string {
  if (typeof value === "string") return value;
  if (Array.isArray(value)) return value.map(readText).filter(Boolean).join("\n");
  const record = asRecord(value);
  if (!record) return "";
  return (
    readString(record.text) ||
    readText(record.content) ||
    readString(record.message) ||
    readString(record.value) ||
    readString(record.step) ||
    readString(record.title)
  );
}

function threadStatus(state: Record<string, unknown>): string {
  const runtime = asRecord(state.threadRuntimeStatus);
  const runtimeType = readStatus(runtime?.type ?? state.status);
  if (runtimeType) return runtimeType;
  const turns = orderedTurns(state);
  return readStatus(asRecord(turns.at(-1))?.status) || "idle";
}

function extractCwd(state: Record<string, unknown>): string {
  const thread = asRecord(state.thread);
  const metadata = asRecord(state.metadata);
  const settings = asRecord(state.latestThreadSettings) ?? asRecord(state.threadSettings);
  const direct = (
    readString(state.cwd) ||
    readString(state.workingDirectory) ||
    readString(thread?.cwd) ||
    readString(metadata?.cwd) ||
    readString(settings?.cwd)
  );
  if (direct) return direct;
  const gitInfo = asRecord(state.gitInfo) ?? asRecord(asRecord(state.metadata)?.gitInfo);
  return readString(gitInfo?.repositoryRoot) || readString(gitInfo?.root) || "";
}

function extractGitInfo(state: Record<string, unknown>): GitInfoSummary | null {
  const source = asRecord(state.gitInfo) ?? asRecord(asRecord(state.metadata)?.gitInfo);
  if (!source) return null;
  const branch =
    readString(source.branch) ||
    readString(source.currentBranch) ||
    readString(asRecord(source.branch)?.name);
  const repositoryRoot =
    readString(source.repositoryRoot) ||
    readString(source.root) ||
    readString(source.worktreeRoot);
  const sha = readString(source.sha) || readString(source.head) || readString(source.commit);
  const isDirty = typeof source.isDirty === "boolean" ? source.isDirty : undefined;
  const result: GitInfoSummary = {
    ...(branch ? { branch } : {}),
    ...(repositoryRoot ? { repositoryRoot } : {}),
    ...(sha ? { sha } : {}),
    ...(isDirty !== undefined ? { isDirty } : {}),
  };
  return Object.keys(result).length > 0 ? result : null;
}

function extractThreadSettings(
  state: Record<string, unknown>,
): ThreadSettingsSummary | null {
  const source = asRecord(state.latestThreadSettings) ?? asRecord(state.threadSettings);
  if (!source) return null;
  const model = readString(source.model);
  const effort = readString(source.effort) || readString(source.reasoningEffort);
  const serviceTier = readString(source.serviceTier);
  const result: ThreadSettingsSummary = {
    ...(model ? { model } : {}),
    ...(effort ? { effort } : {}),
    ...(serviceTier ? { serviceTier } : {}),
  };
  return Object.keys(result).length > 0 ? result : null;
}

function extractActiveTurnId(state: Record<string, unknown>): string {
  const runtime = asRecord(state.threadRuntimeStatus);
  const direct =
    readString(runtime?.turnId) ||
    readString(runtime?.activeTurnId) ||
    readString(state.activeTurnId);
  if (direct) return direct;
  const turns = orderedTurns(state);
  for (let index = turns.length - 1; index >= 0; index -= 1) {
    const turn = asRecord(turns[index]);
    if (!turn) continue;
    const status = readStatus(turn.status).toLowerCase();
    if (status === "inprogress" || status === "active" || status === "running") {
      return readString(turn.id) || readString(turn.turnId);
    }
  }
  return "";
}

function normalizeCwdGroupKey(cwd: string): string {
  const normalized = cwd.replace(/\\/g, "/").replace(/\/+$/, "");
  return /^[A-Za-z]:\//.test(normalized) ? normalized.toLowerCase() : normalized;
}

function cwdLabel(cwd: string): string {
  const parts = cwd.replace(/\\/g, "/").replace(/\/+$/, "").split("/");
  return parts.at(-1) || cwd;
}

function isFileChangeType(value: string): boolean {
  const type = value.toLowerCase().replace(/[-_]/g, "");
  return type === "filechange" || type.includes("patch");
}

function readUnifiedDiff(value: unknown): string {
  if (typeof value === "string") return value;
  const record = asRecord(value);
  if (!record) return "";
  const nested = record.unifiedDiff ?? record.diff ?? record.patch ?? record.text;
  return typeof nested === "string" ? nested : "";
}

function readStatus(value: unknown): string {
  if (typeof value === "string") return value;
  const record = asRecord(value);
  return record ? readStatus(record.type ?? record.status ?? record.state) : "";
}

function readString(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}
