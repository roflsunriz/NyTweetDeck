import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { CdpClient } from "../cdp-client";
import { makeCapturedAssetFileName, sanitizeOfficialAssetUrl, sha256 } from "./x-reference-policy";

interface BrowserVersionEndpoint {
  Browser: string;
  "Protocol-Version": string;
  webSocketDebuggerUrl: string;
}

interface DebugTarget {
  id: string;
  type: string;
  webSocketDebuggerUrl?: string;
}

interface TargetCreationResult {
  targetId: string;
}

interface ResponseReceivedEvent {
  requestId: string;
  response: {
    mimeType: string;
    status: number;
    url: string;
  };
  type: string;
}

interface LoadingFinishedEvent {
  requestId: string;
}

interface ResponseBody {
  base64Encoded: boolean;
  body: string;
}

interface CapturedResponse {
  body: Uint8Array;
  mimeType: string;
  status: number;
  url: string;
}

interface StructureObservation {
  accountNavigationCandidateCount: number;
  actionButtonCount: number;
  articleCount: number;
  imageCount: number;
  elementInventory: {
    interactiveStates: Record<string, number>;
    roles: Record<string, number>;
    safeTestIds: Record<string, number>;
    tags: Record<string, number>;
  };
  expectedRouteReached: boolean;
  loggedIn: boolean;
  routeShape: string;
  routeKind: "home" | "login" | "other";
  videoCount: number;
}

const DEFAULT_CDP_ENDPOINT = "http://127.0.0.1:9222";
const DEFAULT_URL = "https://x.com/home";
const DEFAULT_SETTLE_MS = 12_000;
const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isBrowserVersionEndpoint(value: unknown): value is BrowserVersionEndpoint {
  return (
    isRecord(value) &&
    typeof value.Browser === "string" &&
    typeof value["Protocol-Version"] === "string" &&
    typeof value.webSocketDebuggerUrl === "string"
  );
}

function isDebugTarget(value: unknown): value is DebugTarget {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.type === "string" &&
    (value.webSocketDebuggerUrl === undefined || typeof value.webSocketDebuggerUrl === "string")
  );
}

function isTargetCreationResult(value: unknown): value is TargetCreationResult {
  return isRecord(value) && typeof value.targetId === "string";
}

function isResponseReceivedEvent(value: unknown): value is ResponseReceivedEvent {
  return (
    isRecord(value) &&
    typeof value.requestId === "string" &&
    typeof value.type === "string" &&
    isRecord(value.response) &&
    typeof value.response.mimeType === "string" &&
    typeof value.response.status === "number" &&
    typeof value.response.url === "string"
  );
}

function isLoadingFinishedEvent(value: unknown): value is LoadingFinishedEvent {
  return isRecord(value) && typeof value.requestId === "string";
}

function isResponseBody(value: unknown): value is ResponseBody {
  return (
    isRecord(value) && typeof value.base64Encoded === "boolean" && typeof value.body === "string"
  );
}

function parseArgument(name: string): string | undefined {
  const prefix = `--${name}=`;
  return process.argv
    .slice(2)
    .find((argument) => argument.startsWith(prefix))
    ?.slice(prefix.length);
}

function validateSurfaceId(value: string): string {
  if (!/^[a-z][a-z0-9-]{1,80}$/.test(value)) {
    throw new Error(`surface IDが不正です: ${value}`);
  }
  return value;
}

function validateTargetUrl(value: string): string {
  const url = new URL(value);
  if (url.protocol !== "https:" || url.hostname !== "x.com") {
    throw new Error(`X公式Web以外は観測できません: ${value}`);
  }
  url.search = "";
  url.hash = "";
  return url.href;
}

function timestampForPath(date: Date): string {
  return date.toISOString().replace(/[:.]/g, "-");
}

async function fetchJson(endpoint: string): Promise<unknown> {
  const response = await fetch(endpoint);
  if (!response.ok) {
    throw new Error(`${endpoint} がHTTP ${response.status}を返しました。`);
  }
  return response.json();
}

async function waitForTargetWebSocket(cdpEndpoint: string, targetId: string): Promise<string> {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const value = await fetchJson(`${cdpEndpoint}/json/list`);
    if (Array.isArray(value)) {
      const target = value.find(
        (candidate): candidate is DebugTarget =>
          isDebugTarget(candidate) && candidate.id === targetId,
      );
      if (target?.webSocketDebuggerUrl) return target.webSocketDebuggerUrl;
    }
    await Bun.sleep(100);
  }
  throw new Error(`CDP target ${targetId} のWebSocketを特定できませんでした。`);
}

async function observeStructure(
  client: CdpClient,
  expectedPath: string,
): Promise<StructureObservation> {
  return client.evaluate<StructureObservation>(`(() => {
    const posts = [...document.querySelectorAll('article[data-testid="tweet"]')];
    const path = location.pathname;
    const safeRouteSegments = new Set(['all', 'bookmarks', 'communities', 'compose', 'explore', 'grok', 'home', 'i', 'inbox', 'lists', 'messages', 'notifications', 'post', 'premium_sign_up', 'search', 'settings']);
    const routeShape = path.split('/').map((segment, index) =>
      index === 0 || safeRouteSegments.has(segment) ? segment : ':dynamic'
    ).join('/');
    const countValues = (values) => values.reduce((counts, value) => {
      if (value) counts[value] = (counts[value] ?? 0) + 1;
      return counts;
    }, {});
    const safeTestIdPatterns = [
      /^(?:tweet|tweetText|tweetPhoto|User-Name|reply|retweet|like|bookmark|caret|primaryColumn|cellInnerDiv)$/,
      /^(?:SideNav|AppTabBar)_[A-Za-z_]+$/
    ];
    const safeTestIds = [...document.querySelectorAll('[data-testid]')]
      .map((node) => node.getAttribute('data-testid') ?? '')
      .filter((value) => safeTestIdPatterns.some((pattern) => pattern.test(value)));
    const tags = ['a', 'article', 'aside', 'button', 'dialog', 'form', 'header', 'img', 'input', 'main', 'nav', 'select', 'textarea', 'video'];
    const interactiveStates = {
      ariaExpandedFalse: document.querySelectorAll('[aria-expanded="false"]').length,
      ariaExpandedTrue: document.querySelectorAll('[aria-expanded="true"]').length,
      ariaPressedFalse: document.querySelectorAll('[aria-pressed="false"]').length,
      ariaPressedTrue: document.querySelectorAll('[aria-pressed="true"]').length,
      ariaSelectedFalse: document.querySelectorAll('[aria-selected="false"]').length,
      ariaSelectedTrue: document.querySelectorAll('[aria-selected="true"]').length,
      disabled: document.querySelectorAll(':disabled, [aria-disabled="true"]').length,
      modal: document.querySelectorAll('[aria-modal="true"]').length
    };
    return {
      accountNavigationCandidateCount: posts.reduce((count, post) =>
        count + post.querySelectorAll('[data-testid="User-Name"] a, a[href^="/"] img').length, 0),
      actionButtonCount: posts.reduce((count, post) =>
        count + post.querySelectorAll('[data-testid="reply"], [data-testid="retweet"], [data-testid="like"], [data-testid="bookmark"]').length, 0),
      articleCount: posts.length,
      imageCount: posts.reduce((count, post) => count + post.querySelectorAll('[data-testid="tweetPhoto"] img').length, 0),
      elementInventory: {
        interactiveStates,
        roles: countValues([...document.querySelectorAll('[role]')].map((node) => node.getAttribute('role') ?? '').filter((value) => /^[a-z][a-z0-9-]{1,40}$/.test(value))),
        safeTestIds: countValues(safeTestIds),
        tags: Object.fromEntries(tags.map((tag) => [tag, document.querySelectorAll(tag).length]))
      },
      expectedRouteReached: path === ${JSON.stringify(expectedPath)},
      loggedIn: document.querySelector('[data-testid="SideNav_AccountSwitcher_Button"]') !== null,
      routeShape,
      routeKind: path === '/home' ? 'home' : path.startsWith('/i/flow/login') ? 'login' : 'other',
      videoCount: posts.reduce((count, post) => count + post.querySelectorAll('video').length, 0)
    };
  })()`);
}

async function main(): Promise<void> {
  const cdpEndpoint = parseArgument("cdp") ?? DEFAULT_CDP_ENDPOINT;
  const targetUrl = validateTargetUrl(parseArgument("url") ?? DEFAULT_URL);
  const surfaceId = validateSurfaceId(parseArgument("surface-id") ?? "home-for-you");
  const settleMs = Number.parseInt(parseArgument("settle-ms") ?? String(DEFAULT_SETTLE_MS), 10);
  if (!Number.isFinite(settleMs) || settleMs < 0 || settleMs > 60_000) {
    throw new Error("--settle-msは0から60000の整数で指定してください。");
  }

  const capturedAt = new Date();
  const captureName = timestampForPath(capturedAt);
  const outputRoot = join(SCRIPT_DIRECTORY, "..", "..", "src", "sandbox", "x-reference-captures");
  const captureDirectory = join(outputRoot, "captures", captureName);
  await mkdir(captureDirectory, { recursive: true });

  const versionValue = await fetchJson(`${cdpEndpoint}/json/version`);
  if (!isBrowserVersionEndpoint(versionValue)) {
    throw new Error("CDP version endpointの応答形式が不正です。");
  }
  const browserClient = await CdpClient.connect(versionValue.webSocketDebuggerUrl);
  let targetId: string | undefined;
  try {
    const created = await browserClient.call<unknown>("Target.createTarget", {
      url: "about:blank",
    });
    if (!isTargetCreationResult(created)) {
      throw new Error("Target.createTargetの応答形式が不正です。");
    }
    targetId = created.targetId;
    const pageWebSocket = await waitForTargetWebSocket(cdpEndpoint, targetId);
    const pageClient = await CdpClient.connect(pageWebSocket);
    const candidates = new Map<string, Omit<CapturedResponse, "body">>();
    const captured = new Map<string, CapturedResponse>();
    const bodyTasks = new Set<Promise<void>>();
    const bodyFailures: string[] = [];

    pageClient.on("Network.responseReceived", (value) => {
      if (!isResponseReceivedEvent(value)) return;
      const url = sanitizeOfficialAssetUrl(value.response.url);
      if (url === null) return;
      candidates.set(value.requestId, {
        mimeType: value.response.mimeType,
        status: value.response.status,
        url,
      });
    });
    pageClient.on("Network.loadingFinished", (value) => {
      if (!isLoadingFinishedEvent(value)) return;
      const candidate = candidates.get(value.requestId);
      if (candidate === undefined || captured.has(candidate.url)) return;
      const task = pageClient
        .call<unknown>("Network.getResponseBody", { requestId: value.requestId })
        .then((bodyValue) => {
          if (!isResponseBody(bodyValue)) {
            throw new Error("応答形式が不正です。");
          }
          const body = bodyValue.base64Encoded
            ? Uint8Array.from(Buffer.from(bodyValue.body, "base64"))
            : new TextEncoder().encode(bodyValue.body);
          captured.set(candidate.url, { ...candidate, body });
        })
        .catch((error: unknown) => {
          bodyFailures.push(
            `${candidate.url}: ${error instanceof Error ? error.message : String(error)}`,
          );
        })
        .finally(() => bodyTasks.delete(task));
      bodyTasks.add(task);
    });

    let observation: StructureObservation;
    try {
      await Promise.all([
        pageClient.call("Page.enable"),
        pageClient.call("Runtime.enable"),
        pageClient.call("Network.enable", {
          maxResourceBufferSize: 25_000_000,
          maxTotalBufferSize: 200_000_000,
        }),
      ]);
      await pageClient.call("Network.setCacheDisabled", { cacheDisabled: true });
      await pageClient.call("Network.setBypassServiceWorker", { bypass: true });
      const loaded = pageClient.waitForEvent("Page.loadEventFired", 45_000);
      await pageClient.call("Page.navigate", { url: targetUrl });
      await loaded;
      await Bun.sleep(settleMs);
      while (bodyTasks.size > 0) {
        await Promise.allSettled([...bodyTasks]);
      }
      observation = await observeStructure(pageClient, new URL(targetUrl).pathname);
    } finally {
      pageClient.close();
    }

    const files = [];
    for (const response of [...captured.values()].sort((left, right) =>
      left.url.localeCompare(right.url),
    )) {
      const fileName = makeCapturedAssetFileName(response.url);
      await writeFile(join(captureDirectory, fileName), response.body);
      files.push({
        bytes: response.body.byteLength,
        fileName,
        mimeType: response.mimeType,
        sha256: sha256(response.body),
        status: response.status,
        url: response.url,
      });
    }
    if (files.length === 0) {
      throw new Error("X公式Web資産を1件も取得できませんでした。");
    }

    const manifest = {
      authenticationMaterialStored: false,
      browser: versionValue.Browser,
      capturedAt: capturedAt.toISOString(),
      domStored: false,
      files,
      htmlStored: false,
      observation,
      protocolVersion: versionValue["Protocol-Version"],
      requestHeadersStored: false,
      responseHeadersStored: false,
      schemaVersion: 1,
      screenshotStored: false,
      surfaceId,
      target: new URL(targetUrl).pathname,
      transientBodyCaptureFailures: bodyFailures,
    };
    await writeFile(
      join(captureDirectory, "manifest.json"),
      `${JSON.stringify(manifest, null, 2)}\n`,
      "utf8",
    );
    await writeFile(
      join(outputRoot, "latest.json"),
      `${JSON.stringify({ captureDirectory: `captures/${captureName}`, schemaVersion: 1 }, null, 2)}\n`,
      "utf8",
    );
    const totalBytes = files.reduce((sum, file) => sum + file.bytes, 0);
    console.log(
      `取得完了: ${files.length} files / ${totalBytes} bytes / loggedIn=${observation.loggedIn} / posts=${observation.articleCount}`,
    );
  } finally {
    if (targetId !== undefined) {
      await browserClient.call("Target.closeTarget", { targetId }).catch(() => undefined);
    }
    browserClient.close();
  }
}

await main();
