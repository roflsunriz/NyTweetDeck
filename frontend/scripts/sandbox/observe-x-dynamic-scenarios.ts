import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { CdpClient } from "../cdp-client";
import { makeCapturedAssetFileName, sanitizeOfficialAssetUrl, sha256 } from "./x-reference-policy";
import {
  type ReadOnlyDynamicAction,
  READ_ONLY_DYNAMIC_SCENARIOS,
} from "./x-reference-dynamic-scenario-catalog";

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

interface SafeSnapshot {
  articleCount: number;
  dialogCount: number;
  expandedCount: number;
  interactiveSignatures: Array<{
    height: number;
    hrefShape: string | null;
    index: number;
    role: string;
    tag: string;
    testIdFingerprint: string | null;
    width: number;
    x: number;
    y: number;
  }>;
  menuCount: number;
  modalCount: number;
  navigationTestIds: Record<string, number>;
  progressCount: number;
  roleCounts: Record<string, number>;
  routeShape: string;
  selectedTabCount: number;
  videoCount: number;
}

interface ScenarioResult {
  actionPerformed: boolean;
  after: SafeSnapshot;
  before: SafeSnapshot;
  id: string;
  intermediate?: SafeSnapshot;
  officialAssetCount: number;
}

const DEFAULT_CDP_ENDPOINT = "http://127.0.0.1:9222";
const HOME_URL = "https://x.com/home";
const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));

interface Viewport {
  height: number;
  width: number;
}

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

function parseCdpEndpoint(): string {
  const argument = process.argv.slice(2).find((value) => value.startsWith("--cdp="));
  return argument?.slice("--cdp=".length) ?? DEFAULT_CDP_ENDPOINT;
}

function selectedScenarios(): readonly (typeof READ_ONLY_DYNAMIC_SCENARIOS)[number][] {
  const argument = process.argv.slice(2).find((value) => value.startsWith("--scenarios="));
  if (argument === undefined) return READ_ONLY_DYNAMIC_SCENARIOS;
  const requested = new Set(
    argument
      .slice("--scenarios=".length)
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean),
  );
  const known = new Set(READ_ONLY_DYNAMIC_SCENARIOS.map(({ id }) => id));
  const unknown = [...requested].filter((id) => !known.has(id));
  if (unknown.length > 0) throw new Error(`未知のdynamic scenarioです: ${unknown.join(", ")}`);
  return READ_ONLY_DYNAMIC_SCENARIOS.filter(({ id }) => requested.has(id));
}

function parseViewport(): Viewport {
  const argument = process.argv.slice(2).find((value) => value.startsWith("--viewport="));
  const value = argument?.slice("--viewport=".length) ?? "1440x900";
  const match = value.match(/^(?<width>\d{3,4})x(?<height>\d{3,4})$/);
  const width = Number(match?.groups?.width);
  const height = Number(match?.groups?.height);
  if (
    !Number.isInteger(width) ||
    !Number.isInteger(height) ||
    width < 320 ||
    width > 3840 ||
    height < 480 ||
    height > 2160
  ) {
    throw new Error(`viewportが不正です: ${value}`);
  }
  return { height, width };
}

function timestampForPath(date: Date): string {
  return date.toISOString().replace(/[:.]/g, "-");
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

async function navigateHome(client: CdpClient): Promise<void> {
  const loaded = client.waitForEvent("Page.loadEventFired", 45_000);
  await client.call("Page.navigate", { url: HOME_URL });
  await loaded;
  await Bun.sleep(4_000);
}

async function safeSnapshot(client: CdpClient): Promise<SafeSnapshot> {
  return client.evaluate<SafeSnapshot>(`(() => {
    const safeRouteSegments = new Set(['home', 'i', 'compose', 'post']);
    const routeShape = location.pathname.split('/').map((segment, index) =>
      index === 0 || safeRouteSegments.has(segment) ? segment : ':dynamic'
    ).join('/');
    const navigationTestIds = [...document.querySelectorAll('[data-testid]')]
      .map((node) => node.getAttribute('data-testid') ?? '')
      .filter((value) => /^(?:AppTabBar|BottomNav|FloatingActionButton|SideNav)_[A-Za-z_]+$/.test(value))
      .reduce((counts, value) => {
        counts[value] = (counts[value] ?? 0) + 1;
        return counts;
      }, {});
    const fingerprint = (value) => {
      let hash = 2166136261;
      for (let index = 0; index < value.length; index += 1) {
        hash ^= value.charCodeAt(index);
        hash = Math.imul(hash, 16777619);
      }
      return (hash >>> 0).toString(16).padStart(8, '0');
    };
    const routeShapeFor = (value) => {
      try {
        return new URL(value, location.origin).pathname.split('/').map((segment, index) =>
          index === 0 || safeRouteSegments.has(segment) ? segment : ':dynamic'
        ).join('/');
      } catch {
        return null;
      }
    };
    const interactiveSignatures = [...document.querySelectorAll('a[href], button, [role="button"]')]
      .filter((node) => node instanceof HTMLElement && node.getClientRects().length > 0)
      .map((node, index) => {
        const rect = node.getBoundingClientRect();
        const testId = node.getAttribute('data-testid');
        const isKnownNavigation = testId !== null && /^(?:AppTabBar|BottomNav|FloatingActionButton|SideNav)_[A-Za-z_]+$/.test(testId);
        return {
          height: Math.round(rect.height),
          hrefShape: node instanceof HTMLAnchorElement ? routeShapeFor(node.href) : null,
          index,
          role: node.getAttribute('role') ?? '',
          tag: node.tagName.toLowerCase(),
          testIdFingerprint: testId === null ? null : isKnownNavigation ? testId : 'hash:' + fingerprint(testId),
          width: Math.round(rect.width),
          x: Math.round(rect.x),
          y: Math.round(rect.y)
        };
      });
    const roleCounts = [...document.querySelectorAll('[role]')].reduce((counts, node) => {
      const role = node.getAttribute('role') ?? '';
      if (/^[a-z][a-z0-9-]{1,40}$/.test(role)) counts[role] = (counts[role] ?? 0) + 1;
      return counts;
    }, {});
    return {
      articleCount: document.querySelectorAll('article[data-testid="tweet"]').length,
      dialogCount: document.querySelectorAll('[role="dialog"]').length,
      expandedCount: document.querySelectorAll('[aria-expanded="true"]').length,
      interactiveSignatures,
      menuCount: document.querySelectorAll('[role="menu"]').length,
      modalCount: document.querySelectorAll('[aria-modal="true"]').length,
      navigationTestIds,
      progressCount: document.querySelectorAll('[role="progressbar"]').length,
      roleCounts,
      routeShape,
      selectedTabCount: document.querySelectorAll('[role="tab"][aria-selected="true"]').length,
      videoCount: document.querySelectorAll('video').length
    };
  })()`);
}

async function performAction(client: CdpClient, action: ReadOnlyDynamicAction): Promise<boolean> {
  return client.evaluate<boolean>(`(() => {
    const find = (selector) => selector ? [...document.querySelectorAll(selector)] : [];
    let nodes = find(${JSON.stringify(action.selector)});
    if (nodes.length === 0) nodes = find(${JSON.stringify(action.fallbackSelector ?? "")});
    const node = nodes[${action.kind === "click-index" ? action.index : 0}];
    if (!(node instanceof HTMLElement)) return false;
    ${action.kind === "focus" ? "node.focus();" : "node.click();"}
    return true;
  })()`);
}

async function officialAssetUrls(client: CdpClient): Promise<string[]> {
  const values = await client.evaluate<string[]>(`performance.getEntriesByType('resource')
    .map((entry) => entry.name)
    .filter((value) => typeof value === 'string')`);
  return [
    ...new Set(values.map(sanitizeOfficialAssetUrl).filter((url): url is string => url !== null)),
  ];
}

async function main(): Promise<void> {
  const cdpEndpoint = parseCdpEndpoint();
  const viewport = parseViewport();
  const capturedAt = new Date();
  const outputRoot = join(
    SCRIPT_DIRECTORY,
    "..",
    "..",
    "src",
    "sandbox",
    "x-reference-captures",
    "dynamic-scenarios",
  );
  const captureName = timestampForPath(capturedAt);
  const captureDirectory = join(outputRoot, captureName);
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
    if (!isTargetCreationResult(created))
      throw new Error("Target.createTargetの応答形式が不正です。");
    targetId = created.targetId;
    const pageClient = await CdpClient.connect(await waitForTargetWebSocket(cdpEndpoint, targetId));
    const allAssetUrls = new Set<string>();
    const results: ScenarioResult[] = [];
    try {
      await Promise.all([
        pageClient.call("Network.enable"),
        pageClient.call("Page.enable"),
        pageClient.call("Runtime.enable"),
      ]);
      await pageClient.call("Emulation.setDeviceMetricsOverride", {
        deviceScaleFactor: 1,
        height: viewport.height,
        mobile: viewport.width < 600,
        width: viewport.width,
      });
      await pageClient.call("Emulation.setTouchEmulationEnabled", {
        enabled: viewport.width < 600,
        maxTouchPoints: viewport.width < 600 ? 5 : 1,
      });
      await pageClient.call("Network.setCacheDisabled", { cacheDisabled: true });
      await pageClient.call("Network.setBypassServiceWorker", { bypass: true });
      for (const scenario of selectedScenarios()) {
        await navigateHome(pageClient);
        const before = await safeSnapshot(pageClient);
        const actionPerformed = await performAction(pageClient, scenario.action);
        await Bun.sleep(600);
        const intermediate = await safeSnapshot(pageClient);
        if (scenario.action.kind === "click-then-escape" && actionPerformed) {
          await pageClient.call("Input.dispatchKeyEvent", {
            code: "Escape",
            key: "Escape",
            type: "keyDown",
          });
          await pageClient.call("Input.dispatchKeyEvent", {
            code: "Escape",
            key: "Escape",
            type: "keyUp",
          });
        }
        await Bun.sleep(900);
        const after = await safeSnapshot(pageClient);
        const urls = await officialAssetUrls(pageClient);
        urls.forEach((url) => {
          allAssetUrls.add(url);
        });
        results.push({
          actionPerformed,
          after,
          before,
          id: scenario.id,
          ...(scenario.action.kind === "click-then-escape" ? { intermediate } : {}),
          officialAssetCount: urls.length,
        });
        console.log(
          `${scenario.id}: action=${actionPerformed} route=${after.routeShape} dialog=${after.dialogCount} menu=${after.menuCount}`,
        );
      }
    } finally {
      pageClient.close();
    }

    const files = [];
    const failures = [];
    for (const url of [...allAssetUrls].sort()) {
      try {
        const response = await fetch(url, { credentials: "omit" });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const body = new Uint8Array(await response.arrayBuffer());
        if (body.byteLength > 25_000_000) throw new Error("size limit");
        const fileName = makeCapturedAssetFileName(url);
        await writeFile(join(captureDirectory, fileName), body);
        files.push({
          bytes: body.byteLength,
          fileName,
          mimeType:
            response.headers.get("content-type")?.split(";")[0] ?? "application/octet-stream",
          sha256: sha256(body),
          status: response.status,
          url,
        });
      } catch (error) {
        failures.push({
          error: error instanceof Error ? error.message.slice(0, 200) : "unknown",
          url,
        });
      }
    }
    const manifest = {
      authenticationMaterialStored: false,
      browser: versionValue.Browser,
      capturedAt: capturedAt.toISOString(),
      domStored: false,
      files,
      htmlStored: false,
      mutationPerformed: false,
      protocolVersion: versionValue["Protocol-Version"],
      requestHeadersStored: false,
      resourceFailures: failures,
      responseHeadersStored: false,
      results,
      schemaVersion: 1,
      screenshotStored: false,
      viewport,
    };
    await writeFile(
      join(captureDirectory, "manifest.json"),
      `${JSON.stringify(manifest, null, 2)}\n`,
      "utf8",
    );
    await writeFile(
      join(outputRoot, "latest.json"),
      `${JSON.stringify({ captureDirectory: captureName, schemaVersion: 1 }, null, 2)}\n`,
      "utf8",
    );
    console.log(
      `動的scenario観測完了: ${results.filter(({ actionPerformed }) => actionPerformed).length}/${results.length}操作、${files.length}資産、${failures.length}取得失敗`,
    );
  } finally {
    if (targetId !== undefined) {
      await browserClient.call("Target.closeTarget", { targetId }).catch(() => undefined);
    }
    browserClient.close();
  }
}

await main();
