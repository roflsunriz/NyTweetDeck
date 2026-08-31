import { createServer, type Server } from "node:http";
import { writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { CdpClient } from "../cdp-client";
import { isAllowedLoopbackRequest } from "./offline-sandbox-policy";

interface BrowserVersionEndpoint {
  Browser: string;
  webSocketDebuggerUrl: string;
}

interface BrowserContextResult {
  browserContextId: string;
}

interface TargetCreationResult {
  targetId: string;
}

interface DebugTarget {
  id: string;
  type: string;
  webSocketDebuggerUrl?: string;
}

interface FetchRequestPausedEvent {
  request: { url: string };
  requestId: string;
}

interface CookieResult {
  cookies: unknown[];
}

interface OfflineRuntimeResult {
  externalHttp: "blocked";
  externalHttps: "blocked";
  localStorageLength: number;
  mockEntryCount: number;
  renderedKinds: string[];
  sessionStorageLength: number;
}

const DEFAULT_CDP_ENDPOINT = "http://127.0.0.1:9222";
const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const CAPTURE_ROOT = join(SCRIPT_DIRECTORY, "..", "..", "src", "sandbox", "x-reference-captures");

const MOCK_TIMELINE = {
  entries: [
    { authorId: "fixture-author", id: "fixture-text", kind: "text", url: "https://example.test/a" },
    { authorId: "fixture-author", id: "fixture-image", kind: "image" },
    { authorId: "fixture-author", id: "fixture-video", kind: "video" },
    { authorId: "fixture-parent", id: "fixture-reply", kind: "reply", parentId: "fixture-root" },
    { authorId: "fixture-profile", id: "fixture-account", kind: "account-link" },
  ],
  schemaVersion: 1,
} as const;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isBrowserVersionEndpoint(value: unknown): value is BrowserVersionEndpoint {
  return (
    isRecord(value) &&
    typeof value.Browser === "string" &&
    typeof value.webSocketDebuggerUrl === "string"
  );
}

function isBrowserContextResult(value: unknown): value is BrowserContextResult {
  return isRecord(value) && typeof value.browserContextId === "string";
}

function isTargetCreationResult(value: unknown): value is TargetCreationResult {
  return isRecord(value) && typeof value.targetId === "string";
}

function isDebugTarget(value: unknown): value is DebugTarget {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.type === "string" &&
    (value.webSocketDebuggerUrl === undefined || typeof value.webSocketDebuggerUrl === "string")
  );
}

function isFetchRequestPausedEvent(value: unknown): value is FetchRequestPausedEvent {
  return (
    isRecord(value) &&
    typeof value.requestId === "string" &&
    isRecord(value.request) &&
    typeof value.request.url === "string"
  );
}

function isCookieResult(value: unknown): value is CookieResult {
  return isRecord(value) && Array.isArray(value.cookies);
}

function isOfflineRuntimeResult(value: unknown): value is OfflineRuntimeResult {
  return (
    isRecord(value) &&
    value.externalHttp === "blocked" &&
    value.externalHttps === "blocked" &&
    typeof value.localStorageLength === "number" &&
    typeof value.mockEntryCount === "number" &&
    Array.isArray(value.renderedKinds) &&
    value.renderedKinds.every((kind) => typeof kind === "string") &&
    typeof value.sessionStorageLength === "number"
  );
}

function parseCdpEndpoint(): string {
  const argument = process.argv.slice(2).find((value) => value.startsWith("--cdp="));
  return argument?.slice("--cdp=".length) ?? DEFAULT_CDP_ENDPOINT;
}

async function fetchJson(endpoint: string): Promise<unknown> {
  const response = await fetch(endpoint);
  if (!response.ok) throw new Error(`${endpoint} がHTTP ${response.status}を返しました。`);
  return response.json();
}

async function waitForTargetWebSocket(cdpEndpoint: string, targetId: string): Promise<string> {
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const targets = await fetchJson(`${cdpEndpoint}/json/list`);
    if (Array.isArray(targets)) {
      const target = targets.find(
        (candidate): candidate is DebugTarget =>
          isDebugTarget(candidate) && candidate.id === targetId,
      );
      if (target?.webSocketDebuggerUrl) return target.webSocketDebuggerUrl;
    }
    await Bun.sleep(100);
  }
  throw new Error(`CDP target ${targetId} のWebSocketを特定できませんでした。`);
}

async function listen(server: Server): Promise<number> {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", reject);
      const address = server.address();
      if (address === null || typeof address === "string") {
        reject(new Error("loopback sandbox serverのportを取得できません。"));
        return;
      }
      resolve(address.port);
    });
  });
}

async function closeServer(server: Server): Promise<void> {
  return new Promise((resolve, reject) => {
    server.close((error) => (error === undefined ? resolve() : reject(error)));
  });
}

function createSandboxServer(): Server {
  return createServer((request, response) => {
    const requestUrl = new URL(request.url ?? "/", "http://127.0.0.1");
    if (requestUrl.pathname === "/mock/timeline.json") {
      response.writeHead(200, {
        "Cache-Control": "no-store",
        "Content-Type": "application/json; charset=utf-8",
      });
      response.end(JSON.stringify(MOCK_TIMELINE));
      return;
    }
    if (requestUrl.pathname === "/sandbox.html") {
      response.writeHead(200, {
        "Cache-Control": "no-store",
        "Content-Security-Policy":
          "default-src 'none'; script-src 'unsafe-inline'; connect-src 'self'; img-src data: blob:; media-src blob:; style-src 'unsafe-inline'; worker-src 'none'; frame-src 'none'",
        "Content-Type": "text/html; charset=utf-8",
      });
      response.end(`<!doctype html>
<html lang="ja"><head><meta charset="utf-8"><title>X reference offline sandbox</title></head>
<body><main id="root"></main><script>
(async () => {
  const timeline = await fetch('/mock/timeline.json').then((response) => response.json());
  const root = document.querySelector('#root');
  for (const entry of timeline.entries) {
    const article = document.createElement('article');
    article.dataset.kind = entry.kind;
    article.dataset.fixtureId = entry.id;
    root.appendChild(article);
  }
  document.documentElement.dataset.sandboxReady = 'true';
})();
</script></body></html>`);
      return;
    }
    response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("not found");
  });
}

async function main(): Promise<void> {
  const cdpEndpoint = parseCdpEndpoint();
  const server = createSandboxServer();
  const port = await listen(server);
  const versionValue = await fetchJson(`${cdpEndpoint}/json/version`);
  if (!isBrowserVersionEndpoint(versionValue)) {
    throw new Error("CDP version endpointの応答形式が不正です。");
  }
  const browserClient = await CdpClient.connect(versionValue.webSocketDebuggerUrl);
  let browserContextId: string | undefined;
  let targetId: string | undefined;
  try {
    const context = await browserClient.call<unknown>("Target.createBrowserContext", {
      disposeOnDetach: true,
    });
    if (!isBrowserContextResult(context)) {
      throw new Error("Target.createBrowserContextの応答形式が不正です。");
    }
    browserContextId = context.browserContextId;
    const target = await browserClient.call<unknown>("Target.createTarget", {
      browserContextId,
      url: "about:blank",
    });
    if (!isTargetCreationResult(target)) {
      throw new Error("Target.createTargetの応答形式が不正です。");
    }
    targetId = target.targetId;
    const pageClient = await CdpClient.connect(await waitForTargetWebSocket(cdpEndpoint, targetId));
    const interceptionTasks = new Set<Promise<void>>();
    pageClient.on("Fetch.requestPaused", (value) => {
      if (!isFetchRequestPausedEvent(value)) return;
      const task = pageClient
        .call<unknown>(
          isAllowedLoopbackRequest(value.request.url, port)
            ? "Fetch.continueRequest"
            : "Fetch.failRequest",
          isAllowedLoopbackRequest(value.request.url, port)
            ? { requestId: value.requestId }
            : { errorReason: "BlockedByClient", requestId: value.requestId },
        )
        .then(() => undefined)
        .finally(() => interceptionTasks.delete(task));
      interceptionTasks.add(task);
    });

    try {
      await Promise.all([
        pageClient.call("Fetch.enable", { patterns: [{ urlPattern: "*" }] }),
        pageClient.call("Network.enable"),
        pageClient.call("Page.enable"),
        pageClient.call("Runtime.enable"),
      ]);
      await pageClient.call("Network.setBlockedURLs", {
        urls: ["ftp://*", "ws://*", "wss://*"],
      });
      const loaded = pageClient.waitForEvent("Page.loadEventFired", 15_000);
      await pageClient.call("Page.navigate", {
        url: `http://127.0.0.1:${port}/sandbox.html`,
      });
      await loaded;
      const result = await pageClient.evaluate<OfflineRuntimeResult>(`(async () => {
        const deadline = Date.now() + 5000;
        while (document.documentElement.dataset.sandboxReady !== 'true') {
          if (Date.now() > deadline) throw new Error('mock rendering timeout');
          await new Promise((resolve) => setTimeout(resolve, 20));
        }
        const blocked = (url) => fetch(url).then(() => 'unexpected-success', () => 'blocked');
        return {
          externalHttp: await blocked('http://example.com/nytweetdeck-offline-probe'),
          externalHttps: await blocked('https://example.com/nytweetdeck-offline-probe'),
          localStorageLength: localStorage.length,
          mockEntryCount: document.querySelectorAll('article[data-kind]').length,
          renderedKinds: [...document.querySelectorAll('article[data-kind]')].map((node) => node.dataset.kind),
          sessionStorageLength: sessionStorage.length
        };
      })()`);
      const cookies = await pageClient.call<unknown>("Network.getAllCookies");
      if (!isCookieResult(cookies) || !isOfflineRuntimeResult(result)) {
        throw new Error("オフラインsandboxの観測形式が不正です。");
      }
      if (
        cookies.cookies.length !== 0 ||
        result.localStorageLength !== 0 ||
        result.sessionStorageLength !== 0 ||
        result.mockEntryCount !== MOCK_TIMELINE.entries.length
      ) {
        throw new Error(`オフラインsandboxの隔離条件を満たしません: ${JSON.stringify(result)}`);
      }
      await writeFile(
        join(CAPTURE_ROOT, "offline-sandbox-runtime.json"),
        `${JSON.stringify(
          {
            browser: versionValue.Browser,
            cookiesStored: false,
            externalNetworkBlocked: true,
            isolatedBrowserContext: true,
            mockEntryCount: result.mockEntryCount,
            renderedKinds: result.renderedKinds,
            schemaVersion: 1,
            storageEmpty: true,
            verifiedAt: new Date().toISOString(),
          },
          null,
          2,
        )}\n`,
        "utf8",
      );
      console.log(
        `隔離確認完了: 外部HTTP/HTTPS/WebSocket/FTP遮断、Cookie/Storage空、mock ${result.mockEntryCount}件投入`,
      );
    } finally {
      await Promise.allSettled([...interceptionTasks]);
      pageClient.close();
    }
  } finally {
    if (targetId !== undefined) {
      await browserClient.call("Target.closeTarget", { targetId }).catch(() => undefined);
    }
    if (browserContextId !== undefined) {
      await browserClient
        .call("Target.disposeBrowserContext", { browserContextId })
        .catch(() => undefined);
    }
    browserClient.close();
    await closeServer(server);
  }
}

await main();
