import { readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { CdpClient } from "../cdp-client";
import { extractNamedFunction } from "./official-function-extractor";

interface LatestCapture {
  captureDirectory: string;
  schemaVersion: number;
}

interface ManifestFile {
  fileName: string;
  url: string;
}

interface CaptureManifest {
  files: ManifestFile[];
  schemaVersion: number;
}

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

interface CookieResult {
  cookies: unknown[];
}

interface UrlEntity {
  display_url?: string | null;
  expanded_url?: string | null;
  indices: [number, number];
  url: string;
}

interface OfficialUrlNormalizationResult {
  errors: string[];
  result: UrlEntity[];
}

interface RuntimeResult {
  externalFetch: "blocked";
  normalized: OfficialUrlNormalizationResult;
}

const DEFAULT_CDP_ENDPOINT = "http://127.0.0.1:9222";
const OFFICIAL_MODULE_MARKER = "188354(e,t,r)";
const OFFICIAL_EXPORT_NAME = "Rl";
const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const CAPTURE_ROOT = resolve(
  SCRIPT_DIRECTORY,
  "..",
  "..",
  "src",
  "sandbox",
  "x-reference-captures",
);

const MOCK_URL_ENTITIES: UrlEntity[] = [
  {
    display_url: "example.test/complete",
    expanded_url: "https://example.test/complete",
    indices: [0, 14],
    url: "https://t.co/complete",
  },
  { indices: [15, 29], url: "https://t.co/missing-both" },
  {
    expanded_url: "https://example.test/missing-display",
    indices: [30, 44],
    url: "https://t.co/missing-display",
  },
  {
    display_url: "example.test/missing-expanded",
    indices: [45, 59],
    url: "https://t.co/missing-expanded",
  },
  { display_url: "", expanded_url: "", indices: [60, 74], url: "https://t.co/empty" },
];

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

function isCookieResult(value: unknown): value is CookieResult {
  return isRecord(value) && Array.isArray(value.cookies);
}

function isUrlEntity(value: unknown): value is UrlEntity {
  return (
    isRecord(value) &&
    typeof value.url === "string" &&
    Array.isArray(value.indices) &&
    value.indices.length === 2 &&
    value.indices.every((index) => typeof index === "number") &&
    (value.display_url === undefined ||
      value.display_url === null ||
      typeof value.display_url === "string") &&
    (value.expanded_url === undefined ||
      value.expanded_url === null ||
      typeof value.expanded_url === "string")
  );
}

function isRuntimeResult(value: unknown): value is RuntimeResult {
  return (
    isRecord(value) &&
    value.externalFetch === "blocked" &&
    isRecord(value.normalized) &&
    Array.isArray(value.normalized.errors) &&
    value.normalized.errors.every((error) => typeof error === "string") &&
    Array.isArray(value.normalized.result) &&
    value.normalized.result.every(isUrlEntity)
  );
}

function parseCdpEndpoint(): string {
  const argument = process.argv.slice(2).find((value) => value.startsWith("--cdp="));
  return argument?.slice("--cdp=".length) ?? DEFAULT_CDP_ENDPOINT;
}

async function readJson<T>(path: string): Promise<T> {
  return JSON.parse(await readFile(path, "utf8")) as T;
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

function findOfficialUrlNormalizer(source: string): { functionName: string; source: string } {
  const moduleStart = source.indexOf(OFFICIAL_MODULE_MARKER);
  if (moduleStart < 0) {
    throw new Error("公式URL entity moduleを特定できませんでした。");
  }
  const modulePrefix = source.slice(moduleStart, moduleStart + 2_000);
  const exportMatch = modulePrefix.match(
    new RegExp(`${OFFICIAL_EXPORT_NAME}:\\(\\)=>(?<name>[a-zA-Z_$][a-zA-Z0-9_$]*)`),
  );
  const functionName = exportMatch?.groups?.name;
  if (functionName === undefined) {
    throw new Error("公式URL補完関数のlocal名を特定できませんでした。");
  }
  return {
    functionName,
    source: extractNamedFunction(source, functionName, moduleStart),
  };
}

function verifySemantics(result: RuntimeResult): void {
  if (result.normalized.result.length !== MOCK_URL_ENTITIES.length) {
    throw new Error(`公式URL補完の出力件数が不正です: ${result.normalized.result.length}`);
  }
  const [complete, missingBoth, missingDisplay, missingExpanded, empty] = result.normalized.result;
  if (
    complete === undefined ||
    missingBoth === undefined ||
    missingDisplay === undefined ||
    missingExpanded === undefined ||
    empty === undefined ||
    complete.display_url !== "example.test/complete" ||
    complete.expanded_url !== "https://example.test/complete" ||
    missingBoth.display_url !== missingBoth.url ||
    missingBoth.expanded_url !== missingBoth.url ||
    missingDisplay.display_url !== missingDisplay.url ||
    missingDisplay.expanded_url !== "https://example.test/missing-display" ||
    missingExpanded?.display_url !== "example.test/missing-expanded" ||
    missingExpanded.expanded_url !== missingExpanded.url ||
    empty?.display_url !== "" ||
    empty.expanded_url !== "" ||
    result.normalized.errors.length !== 3
  ) {
    throw new Error(`公式URL補完の期待値と一致しません: ${JSON.stringify(result)}`);
  }
}

async function main(): Promise<void> {
  const latest = await readJson<LatestCapture>(join(CAPTURE_ROOT, "latest.json"));
  if (latest.schemaVersion !== 1 || latest.captureDirectory.includes("..")) {
    throw new Error("latest captureの形式が不正です。");
  }
  const captureDirectory = resolve(CAPTURE_ROOT, latest.captureDirectory);
  if (!captureDirectory.startsWith(CAPTURE_ROOT)) {
    throw new Error("latest captureがsandbox外を参照しています。");
  }
  const manifest = await readJson<CaptureManifest>(join(captureDirectory, "manifest.json"));
  const mainAsset = manifest.files.find((file) =>
    /\/responsive-web\/client-web\/main\.[a-zA-Z0-9_-]+\.js$/.test(new URL(file.url).pathname),
  );
  if (mainAsset === undefined) throw new Error("公式main資産がcaptureにありません。");
  const official = findOfficialUrlNormalizer(
    await readFile(join(captureDirectory, mainAsset.fileName), "utf8"),
  );

  const cdpEndpoint = parseCdpEndpoint();
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
    try {
      await Promise.all([pageClient.call("Network.enable"), pageClient.call("Runtime.enable")]);
      await pageClient.call("Network.setBlockedURLs", {
        urls: ["ftp://*", "http://*", "https://*", "ws://*", "wss://*"],
      });
      const expression = `(async () => {
        const normalizeUrlEntities = (${official.source});
        const normalized = normalizeUrlEntities(${JSON.stringify(MOCK_URL_ENTITIES)});
        const externalFetch = await fetch('https://example.com/nytweetdeck-url-contract-probe')
          .then(() => 'unexpected-success', () => 'blocked');
        return { externalFetch, normalized };
      })()`;
      const result = await pageClient.evaluate<unknown>(expression);
      const cookies = await pageClient.call<unknown>("Network.getAllCookies");
      if (!isRuntimeResult(result) || !isCookieResult(cookies) || cookies.cookies.length !== 0) {
        throw new Error("公式URL補完関数の隔離実行結果が不正です。");
      }
      verifySemantics(result);
      await writeFile(
        join(captureDirectory, "url-normalization-runtime.json"),
        `${JSON.stringify(
          {
            browser: versionValue.Browser,
            errorCaseCount: result.normalized.errors.length,
            executedAt: new Date().toISOString(),
            externalNetworkBlocked: true,
            isolatedBrowserContext: true,
            mockCaseCount: MOCK_URL_ENTITIES.length,
            observedRules: {
              emptyStringIsPreserved: true,
              missingDisplayFallsBackToUrl: true,
              missingExpandedFallsBackToUrl: true,
              nullishFieldProducesDiagnostic: true,
              suppliedValuesArePreserved: true,
            },
            officialFunctionExecuted: true,
            officialFunctionInstrumented: false,
            officialModuleId: 188354,
            officialProductCodeStored: false,
            schemaVersion: 1,
            sourceAsset: new URL(mainAsset.url).pathname.split("/").at(-1),
            sourceExport: OFFICIAL_EXPORT_NAME,
          },
          null,
          2,
        )}\n`,
        "utf8",
      );
      console.log(
        `URL意味契約観測完了: mock ${MOCK_URL_ENTITIES.length}件 / 欠落診断 ${result.normalized.errors.length}件 / 外部通信遮断`,
      );
    } finally {
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
  }
}

await main();
