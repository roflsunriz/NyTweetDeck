import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { CdpClient } from "../cdp-client";
import { makeCapturedAssetFileName, sanitizeOfficialAssetUrl, sha256 } from "./x-reference-policy";

interface BrowserVersionEndpoint {
  Browser: string;
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

interface VideoState {
  autoplay: boolean;
  connected: boolean;
  controls: boolean;
  currentTime: number;
  currentTimePositive: boolean;
  hasCurrentSource: boolean;
  loop: boolean;
  muted: boolean;
  networkState: number;
  paused: boolean;
  playbackRate: number;
  playsInline: boolean;
  readyState: number;
  videoCount: number;
  visibleInViewport: boolean;
  volume: number;
}

interface PlayerUiState {
  buttonCount: number;
  controlFingerprints: string[];
  sliderCount: number;
}

interface Point {
  x: number;
  y: number;
}

interface ControlPoint extends Point {
  fingerprint: string;
  relativeX: number;
  relativeY: number;
}

interface OverlayState {
  dialogCount: number;
  fullscreen: boolean;
  menuCount: number;
  pictureInPicture: boolean;
  routeShape: string;
}

interface ControlExperiment {
  controlFingerprint: string;
  controlIndex: number;
  currentTimeDelta: number;
  dialogDelta: number;
  fullscreen: boolean;
  menuDelta: number;
  mutedChanged: boolean;
  pausedChanged: boolean;
  pictureInPicture: boolean;
  playbackRateChanged: boolean;
  relativeX: number;
  relativeY: number;
  routeChanged: boolean;
  volumeChanged: boolean;
}

const DEFAULT_CDP_ENDPOINT = "http://127.0.0.1:9222";
const VIDEO_SEARCH_URL = "https://x.com/search?q=filter%3Avideos&src=typed_query&f=live";
const SCRIPT_DIRECTORY = dirname(fileURLToPath(import.meta.url));

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

async function videoState(client: CdpClient): Promise<VideoState | null> {
  return client.evaluate<VideoState | null>(`(() => {
    const videos = [...document.querySelectorAll('article[data-testid="tweet"] video')];
    const video = videos[0];
    if (!(video instanceof HTMLVideoElement)) return null;
    window.__nytdObservedVideo = video;
    const rect = video.getBoundingClientRect();
    return {
      autoplay: video.autoplay,
      connected: video.isConnected,
      controls: video.controls,
      currentTime: video.currentTime,
      currentTimePositive: video.currentTime > 0,
      hasCurrentSource: video.currentSrc.length > 0,
      loop: video.loop,
      muted: video.muted,
      networkState: video.networkState,
      paused: video.paused,
      playbackRate: video.playbackRate,
      playsInline: video.playsInline,
      readyState: video.readyState,
      videoCount: videos.length,
      visibleInViewport: rect.bottom > 0 && rect.top < innerHeight,
      volume: video.volume
    };
  })()`);
}

async function waitForVideo(client: CdpClient): Promise<VideoState> {
  for (let attempt = 0; attempt < 6; attempt += 1) {
    const state = await videoState(client);
    if (state !== null) return state;
    await client.evaluate(`window.scrollBy(0, Math.max(innerHeight * 0.8, 500))`);
    await Bun.sleep(1_500);
  }
  throw new Error("動画を含む検索結果を観測できませんでした。");
}

async function retainedVideoState(client: CdpClient): Promise<VideoState> {
  return client.evaluate<VideoState>(`(() => {
    const video = window.__nytdObservedVideo;
    if (!(video instanceof HTMLVideoElement)) throw new Error('observed video missing');
    const rect = video.getBoundingClientRect();
    return {
      autoplay: video.autoplay,
      connected: video.isConnected,
      controls: video.controls,
      currentTime: video.currentTime,
      currentTimePositive: video.currentTime > 0,
      hasCurrentSource: video.currentSrc.length > 0,
      loop: video.loop,
      muted: video.muted,
      networkState: video.networkState,
      paused: video.paused,
      playbackRate: video.playbackRate,
      playsInline: video.playsInline,
      readyState: video.readyState,
      videoCount: document.querySelectorAll('video').length,
      visibleInViewport: rect.bottom > 0 && rect.top < innerHeight,
      volume: video.volume
    };
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

async function videoCenter(client: CdpClient): Promise<Point> {
  return client.evaluate<Point>(`(() => {
    const video = window.__nytdObservedVideo;
    if (!(video instanceof HTMLVideoElement)) throw new Error('observed video missing');
    const rect = video.getBoundingClientRect();
    return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
  })()`);
}

async function playerUiState(client: CdpClient): Promise<PlayerUiState> {
  return client.evaluate<PlayerUiState>(`(() => {
    const video = window.__nytdObservedVideo;
    if (!(video instanceof HTMLVideoElement)) throw new Error('observed video missing');
    const article = video.closest('article[data-testid="tweet"]');
    if (!(article instanceof HTMLElement)) throw new Error('video article missing');
    const videoRect = video.getBoundingClientRect();
    const fingerprint = (value) => {
      let hash = 2166136261;
      for (let index = 0; index < value.length; index += 1) {
        hash ^= value.charCodeAt(index);
        hash = Math.imul(hash, 16777619);
      }
      return (hash >>> 0).toString(16).padStart(8, '0');
    };
    const controls = [...article.querySelectorAll('button, input[type="range"], [role="slider"]')]
      .filter((node) => {
        if (!(node instanceof HTMLElement) || node.getClientRects().length === 0) return false;
        const rect = node.getBoundingClientRect();
        return rect.right > videoRect.left && rect.left < videoRect.right &&
          rect.bottom > videoRect.top && rect.top < videoRect.bottom;
      });
    return {
      buttonCount: controls.filter((node) => node.tagName === 'BUTTON').length,
      controlFingerprints: controls.map((node) => {
        const testId = node.getAttribute('data-testid');
        const role = node.getAttribute('role') ?? '';
        return testId ? 'testid:' + fingerprint(testId) : node.tagName.toLowerCase() + ':role:' + role;
      }).sort(),
      sliderCount: controls.filter((node) =>
        node.getAttribute('role') === 'slider' ||
        (node instanceof HTMLInputElement && node.type === 'range')
      ).length
    };
  })()`);
}

async function controlPoints(client: CdpClient): Promise<ControlPoint[]> {
  return client.evaluate<ControlPoint[]>(`(() => {
    const video = window.__nytdObservedVideo;
    if (!(video instanceof HTMLVideoElement)) throw new Error('observed video missing');
    const article = video.closest('article[data-testid="tweet"]');
    if (!(article instanceof HTMLElement)) throw new Error('video article missing');
    const videoRect = video.getBoundingClientRect();
    const fingerprint = (value) => {
      let hash = 2166136261;
      for (let index = 0; index < value.length; index += 1) {
        hash ^= value.charCodeAt(index);
        hash = Math.imul(hash, 16777619);
      }
      return (hash >>> 0).toString(16).padStart(8, '0');
    };
    return [...article.querySelectorAll('button')]
      .filter((node) => {
        if (!(node instanceof HTMLButtonElement) || node.getClientRects().length === 0) return false;
        const rect = node.getBoundingClientRect();
        return rect.right > videoRect.left && rect.left < videoRect.right &&
          rect.bottom > videoRect.top && rect.top < videoRect.bottom;
      })
      .map((node, index) => {
        const rect = node.getBoundingClientRect();
        const structuralValue = [
          String(index),
          node.getAttribute('data-testid') ?? '',
          String(node.querySelectorAll('svg path').length),
          String(Math.round(rect.width)),
          String(Math.round(rect.height))
        ].join('|');
        return {
          fingerprint: fingerprint(structuralValue),
          relativeX: videoRect.width > 0 ? (rect.left + rect.width / 2 - videoRect.left) / videoRect.width : 0,
          relativeY: videoRect.height > 0 ? (rect.top + rect.height / 2 - videoRect.top) / videoRect.height : 0,
          x: rect.left + rect.width / 2,
          y: rect.top + rect.height / 2
        };
      });
  })()`);
}

async function overlayState(client: CdpClient): Promise<OverlayState> {
  return client.evaluate<OverlayState>(`(() => {
    const safe = new Set(['i', 'search']);
    const routeShape = location.pathname.split('/').map((segment, index) =>
      index === 0 || safe.has(segment) ? segment : ':dynamic'
    ).join('/');
    return {
      dialogCount: document.querySelectorAll('[role="dialog"]').length,
      fullscreen: document.fullscreenElement !== null,
      menuCount: document.querySelectorAll('[role="menu"]').length,
      pictureInPicture: document.pictureInPictureElement !== null,
      routeShape
    };
  })()`);
}

async function clickPoint(client: CdpClient, point: Point): Promise<void> {
  await client.call("Input.dispatchMouseEvent", {
    button: "left",
    clickCount: 1,
    type: "mousePressed",
    x: point.x,
    y: point.y,
  });
  await client.call("Input.dispatchMouseEvent", {
    button: "left",
    clickCount: 1,
    type: "mouseReleased",
    x: point.x,
    y: point.y,
  });
}

async function prepareVideoControls(client: CdpClient): Promise<ControlPoint[]> {
  const loaded = client.waitForEvent("Page.loadEventFired", 45_000);
  await client.call("Page.navigate", { url: VIDEO_SEARCH_URL });
  await loaded;
  await Bun.sleep(6_000);
  await waitForVideo(client);
  await client.evaluate(`window.__nytdObservedVideo.scrollIntoView({ block: 'center' })`);
  await Bun.sleep(2_000);
  const center = await videoCenter(client);
  await client.call("Input.dispatchMouseEvent", {
    type: "mouseMoved",
    x: center.x,
    y: center.y,
  });
  await Bun.sleep(700);
  return controlPoints(client);
}

async function observeControlExperiments(client: CdpClient): Promise<ControlExperiment[]> {
  const experiments: ControlExperiment[] = [];
  for (let controlIndex = 0; controlIndex < 5; controlIndex += 1) {
    const controls = await prepareVideoControls(client);
    const control = controls[controlIndex];
    if (control === undefined) continue;
    const beforeVideo = await retainedVideoState(client);
    const beforeOverlay = await overlayState(client);
    await clickPoint(client, control);
    await Bun.sleep(800);
    const afterVideo = await retainedVideoState(client);
    const afterOverlay = await overlayState(client);
    experiments.push({
      controlFingerprint: control.fingerprint,
      controlIndex,
      currentTimeDelta: afterVideo.currentTime - beforeVideo.currentTime,
      dialogDelta: afterOverlay.dialogCount - beforeOverlay.dialogCount,
      fullscreen: afterOverlay.fullscreen,
      menuDelta: afterOverlay.menuCount - beforeOverlay.menuCount,
      mutedChanged: afterVideo.muted !== beforeVideo.muted,
      pausedChanged: afterVideo.paused !== beforeVideo.paused,
      pictureInPicture: afterOverlay.pictureInPicture,
      playbackRateChanged: afterVideo.playbackRate !== beforeVideo.playbackRate,
      relativeX: control.relativeX,
      relativeY: control.relativeY,
      routeChanged: afterOverlay.routeShape !== beforeOverlay.routeShape,
      volumeChanged: afterVideo.volume !== beforeVideo.volume,
    });
    if (afterOverlay.fullscreen) {
      await client.call("Input.dispatchKeyEvent", {
        code: "Escape",
        key: "Escape",
        type: "keyDown",
      });
      await client.call("Input.dispatchKeyEvent", { code: "Escape", key: "Escape", type: "keyUp" });
    }
  }
  return experiments;
}

async function main(): Promise<void> {
  const cdpEndpoint = parseCdpEndpoint();
  const observedAt = new Date();
  const outputRoot = join(
    SCRIPT_DIRECTORY,
    "..",
    "..",
    "src",
    "sandbox",
    "x-reference-captures",
    "video-behavior",
  );
  const captureName = timestampForPath(observedAt);
  const captureDirectory = join(outputRoot, captureName);
  await mkdir(captureDirectory, { recursive: true });

  const versionValue = await fetchJson(`${cdpEndpoint}/json/version`);
  if (!isBrowserVersionEndpoint(versionValue)) throw new Error("CDP version endpoint形式不正");
  const browserClient = await CdpClient.connect(versionValue.webSocketDebuggerUrl);
  let targetId: string | undefined;
  try {
    const target = await browserClient.call<unknown>("Target.createTarget", { url: "about:blank" });
    if (!isTargetCreationResult(target)) throw new Error("Target.createTarget形式不正");
    targetId = target.targetId;
    const pageClient = await CdpClient.connect(await waitForTargetWebSocket(cdpEndpoint, targetId));
    try {
      await Promise.all([
        pageClient.call("Network.enable"),
        pageClient.call("Page.enable"),
        pageClient.call("Runtime.enable"),
      ]);
      await pageClient.call("Emulation.setDeviceMetricsOverride", {
        deviceScaleFactor: 1,
        height: 900,
        mobile: false,
        width: 1440,
      });
      const loaded = pageClient.waitForEvent("Page.loadEventFired", 45_000);
      await pageClient.call("Page.navigate", { url: VIDEO_SEARCH_URL });
      await loaded;
      await Bun.sleep(8_000);

      const discovered = await waitForVideo(pageClient);
      await pageClient.evaluate(`window.__nytdObservedVideo.scrollIntoView({ block: 'center' })`);
      await Bun.sleep(3_000);
      const enteredViewport = await retainedVideoState(pageClient);
      const controlsBeforeHover = await playerUiState(pageClient);
      const center = await videoCenter(pageClient);
      await pageClient.call("Input.dispatchMouseEvent", {
        type: "mouseMoved",
        x: center.x,
        y: center.y,
      });
      await Bun.sleep(1_000);
      const controlsAfterHover = await playerUiState(pageClient);
      await pageClient.evaluate(`window.__nytdObservedScrollY = window.scrollY`);
      await pageClient.evaluate(`window.scrollBy(0, Math.max(innerHeight * 3, 2000))`);
      await Bun.sleep(3_000);
      const leftViewport = await retainedVideoState(pageClient);
      await pageClient.evaluate(`window.scrollTo(0, window.__nytdObservedScrollY)`);
      await Bun.sleep(3_000);
      const reenteredViewport = await waitForVideo(pageClient);
      const controlExperiments = await observeControlExperiments(pageClient);

      const files = [];
      const failures = [];
      for (const url of await officialAssetUrls(pageClient)) {
        try {
          const response = await fetch(url, { credentials: "omit" });
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          const body = new Uint8Array(await response.arrayBuffer());
          const fileName = makeCapturedAssetFileName(url);
          await writeFile(join(captureDirectory, fileName), body);
          files.push({
            bytes: body.byteLength,
            fileName,
            sha256: sha256(body),
            url,
          });
        } catch (error) {
          failures.push(error instanceof Error ? error.message.slice(0, 200) : "unknown");
        }
      }
      const manifest = {
        authenticationMaterialStored: false,
        browser: versionValue.Browser,
        controlsAfterHover,
        controlsBeforeHover,
        controlExperiments,
        discovered,
        enteredViewport,
        files,
        htmlStored: false,
        leftViewport,
        mutationPerformed: false,
        observedAt: observedAt.toISOString(),
        reenteredViewport,
        resourceFailureCount: failures.length,
        schemaVersion: 1,
        sourceUrlStored: false,
        userContentStored: false,
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
        `動画挙動観測完了: enteredPaused=${enteredViewport.paused} leftPaused=${leftViewport.paused} reenteredPaused=${reenteredViewport.paused} assets=${files.length}`,
      );
    } finally {
      pageClient.close();
    }
  } finally {
    if (targetId !== undefined) {
      await browserClient.call("Target.closeTarget", { targetId }).catch(() => undefined);
    }
    browserClient.close();
  }
}

await main();
