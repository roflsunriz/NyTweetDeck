import { resolve } from "node:path";

interface CdpTarget {
  type: string;
  webSocketDebuggerUrl: string;
}

interface CdpMessage {
  id?: number;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: { message: string };
}

interface PendingCall {
  resolve: (value: unknown) => void;
  reject: (reason: Error) => void;
}

class CdpClient {
  private readonly socket: WebSocket;
  private nextId = 1;
  private readonly pending = new Map<number, PendingCall>();
  private readonly eventWaiters = new Map<string, Array<(params: unknown) => void>>();
  private readonly eventListeners = new Map<string, Array<(params: unknown) => void>>();

  private constructor(socket: WebSocket) {
    this.socket = socket;
    this.socket.addEventListener("message", (event) => {
      const message = JSON.parse(String(event.data)) as CdpMessage;
      if (message.id !== undefined) {
        const call = this.pending.get(message.id);
        if (call === undefined) {
          return;
        }
        this.pending.delete(message.id);
        if (message.error !== undefined) {
          call.reject(new Error(message.error.message));
        } else {
          call.resolve(message.result);
        }
        return;
      }

      if (message.method !== undefined) {
        for (const listener of this.eventListeners.get(message.method) ?? []) {
          listener(message.params);
        }
        const waiters = this.eventWaiters.get(message.method) ?? [];
        this.eventWaiters.delete(message.method);
        for (const waiter of waiters) {
          waiter(message.params);
        }
      }
    });
  }

  static async connect(url: string): Promise<CdpClient> {
    const socket = new WebSocket(url);
    await new Promise<void>((resolveOpen, rejectOpen) => {
      socket.addEventListener("open", () => resolveOpen(), { once: true });
      socket.addEventListener("error", () => rejectOpen(new Error("CDP connection failed.")), {
        once: true,
      });
    });
    return new CdpClient(socket);
  }

  call<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
    const id = this.nextId++;
    return new Promise<T>((resolveCall, rejectCall) => {
      this.pending.set(id, {
        resolve: (value) => resolveCall(value as T),
        reject: rejectCall,
      });
      this.socket.send(JSON.stringify({ id, method, params }));
    });
  }

  waitForEvent(method: string, timeoutMilliseconds = 10_000): Promise<unknown> {
    return new Promise((resolveEvent, rejectEvent) => {
      const timeout = setTimeout(
        () => rejectEvent(new Error(`Timed out waiting for ${method}.`)),
        timeoutMilliseconds,
      );
      const waiters = this.eventWaiters.get(method) ?? [];
      waiters.push((params) => {
        clearTimeout(timeout);
        resolveEvent(params);
      });
      this.eventWaiters.set(method, waiters);
    });
  }

  on(method: string, listener: (params: unknown) => void): void {
    const listeners = this.eventListeners.get(method) ?? [];
    listeners.push(listener);
    this.eventListeners.set(method, listeners);
  }

  async evaluate<T>(expression: string): Promise<T> {
    const response = await this.call<{
      result: { value?: T };
      exceptionDetails?: { text: string };
    }>("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
    if (response.exceptionDetails !== undefined) {
      throw new Error(response.exceptionDetails.text);
    }
    return response.result.value as T;
  }

  close(): void {
    this.socket.close();
  }
}

const applicationUrl = process.env.NYTWEETDECK_URL ?? "http://127.0.0.1:18080";
const cdpPort = process.env.CHROME_CDP_PORT ?? "9222";
const targets = (await fetch(`http://127.0.0.1:${cdpPort}/json/list`).then((response) =>
  response.json(),
)) as CdpTarget[];
const page = targets.find((target) => target.type === "page");
if (page === undefined) {
  throw new Error("Chromeの検証用ページが見つかりません。");
}

const client = await CdpClient.connect(page.webSocketDebuggerUrl);
await client.call("Page.enable");
await client.call("Runtime.enable");
await client.call("Log.enable");

const browserErrors: string[] = [];
client.on("Runtime.exceptionThrown", (params) => browserErrors.push(JSON.stringify(params)));
client.on("Runtime.consoleAPICalled", (params) => {
  const event = params as { type?: string };
  if (event.type === "error" || event.type === "assert") {
    browserErrors.push(JSON.stringify(params));
  }
});
client.on("Log.entryAdded", (params) => {
  const event = params as { entry?: { level?: string } };
  if (event.entry?.level === "error") {
    browserErrors.push(JSON.stringify(params));
  }
});

async function navigate(): Promise<void> {
  const loaded = client.waitForEvent("Page.loadEventFired");
  await client.call("Page.navigate", { url: applicationUrl });
  await loaded;
}

async function reload(): Promise<void> {
  const loaded = client.waitForEvent("Page.loadEventFired");
  await client.call("Page.reload", { ignoreCache: true });
  await loaded;
}

async function waitForCondition(expression: string, timeoutMilliseconds = 10_000): Promise<void> {
  const deadline = Date.now() + timeoutMilliseconds;
  while (Date.now() < deadline) {
    if (await client.evaluate<boolean>(expression)) {
      return;
    }
    await Bun.sleep(40);
  }
  const diagnostics = await client.evaluate<Record<string, unknown>>(`(() => {
    const stored = JSON.parse(localStorage.getItem("nytweetdeck.layout") ?? "null");
    return {
      columnCount: document.querySelectorAll(".deck-column").length,
      dialogCount: document.querySelectorAll('[role="dialog"]').length,
      addColumnButtonCount: document.querySelectorAll('[data-action="add-column"]').length,
      homeChoiceCount: document.querySelectorAll('[role="dialog"] [data-column-kind="home"]').length,
      storedColumnCount: Array.isArray(stored?.columns) ? stored.columns.length : null,
      storedActiveAccount: typeof stored?.activeAccountId === "string" ? "selected" : null,
    };
  })()`);
  throw new Error(
    `DOM状態の待機がタイムアウトしました: ${expression}; ${JSON.stringify(diagnostics)}`,
  );
}

await navigate();
await client.evaluate("localStorage.clear()");
await reload();

const viewports = [
  { width: 1440, height: 900, columns: 3 },
  { width: 768, height: 1024, columns: 2 },
  { width: 390, height: 844, columns: 1 },
] as const;

const results: Array<Record<string, unknown>> = [];
for (const viewport of viewports) {
  await client.call("Emulation.setDeviceMetricsOverride", {
    width: viewport.width,
    height: viewport.height,
    deviceScaleFactor: 1,
    mobile: viewport.width <= 390,
  });
  await client.evaluate("localStorage.clear()");
  await reload();

  for (let index = 0; index < viewport.columns; index += 1) {
    const clicked = await client.evaluate<boolean>(`(() => {
      const button = document.querySelector('[data-action="add-column"]');
      if (!(button instanceof HTMLButtonElement)) return false;
      button.click();
      return true;
    })()`);
    if (!clicked) {
      throw new Error("カラム追加ダイアログを開けませんでした。");
    }
    await waitForCondition("document.querySelector('[role=\"dialog\"]') !== null");
    const added = await client.evaluate<boolean>(`(() => {
      const button = document.querySelector('[role="dialog"] [data-column-kind="home"]');
      if (!(button instanceof HTMLButtonElement)) return false;
      button.click();
      return true;
    })()`);
    if (!added) {
      throw new Error("カラムを追加できませんでした。");
    }
    await waitForCondition(`document.querySelectorAll(".deck-column").length === ${index + 1}`);
  }

  const metrics = await client.evaluate<Record<string, unknown>>(`({
    viewport: { width: innerWidth, height: innerHeight },
    documentWidth: document.documentElement.scrollWidth,
    bodyWidth: document.body.getBoundingClientRect().width,
    columnCount: document.querySelectorAll(".deck-column").length,
    dialogCount: document.querySelectorAll('[role="dialog"]').length,
    horizontalOverflow: document.documentElement.scrollWidth > innerWidth,
    interactiveElements: document.querySelectorAll("button, select, a, input").length
  })`);

  const screenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
    format: "png",
    fromSurface: true,
  });
  const screenshotPath = resolve(
    import.meta.dir,
    `../../target/ui-${viewport.width}x${viewport.height}.png`,
  );
  await Bun.write(screenshotPath, Buffer.from(screenshot.data, "base64"));

  await reload();
  const persistedColumns = await client.evaluate<number>(
    'document.querySelectorAll(".deck-column").length',
  );
  if (persistedColumns !== viewport.columns) {
    throw new Error(`カラム永続化に失敗しました: ${persistedColumns}/${viewport.columns}`);
  }

  results.push({ ...metrics, persistedColumns, screenshotPath });
}

await client.call("Emulation.setDeviceMetricsOverride", {
  width: 768,
  height: 1024,
  deviceScaleFactor: 1,
  mobile: false,
});
await reload();
const settingsClicked = await client.evaluate<boolean>(`(() => {
  const button = document.querySelector('[data-action="open-settings"]');
  if (!(button instanceof HTMLButtonElement)) return false;
  button.click();
  return true;
})()`);
if (!settingsClicked) {
  throw new Error("設定ダイアログを開けませんでした。");
}
await waitForCondition("document.querySelector('[role=\"dialog\"]') !== null");
await waitForCondition('document.querySelector("[data-testid=refresh-api-metadata]") !== null');
const settingsMetrics = await client.evaluate<Record<string, unknown>>(`(() => {
  const panel = document.querySelector(".modal-panel");
  if (!(panel instanceof HTMLElement)) return { found: false };
  return {
    found: true,
    viewport: { width: innerWidth, height: innerHeight },
    clientHeight: panel.clientHeight,
    scrollHeight: panel.scrollHeight,
    canScroll: panel.scrollHeight >= panel.clientHeight,
    documentOverflow: document.documentElement.scrollWidth > innerWidth,
    translationHealthFound: document.querySelector("[data-testid=translation-health]") !== null,
    videoLoopChecked: document.querySelector("[data-testid=setting-video-loop]")?.checked,
    videoVolume: document.querySelector("[data-testid=setting-video-volume]")?.value
  };
})()`);
if (
  settingsMetrics.videoLoopChecked !== true ||
  settingsMetrics.videoVolume !== "100" ||
  settingsMetrics.translationHealthFound !== true
) {
  throw new Error(`動画設定の既定値検証に失敗しました: ${JSON.stringify(settingsMetrics)}`);
}
const settingsScreenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
  format: "png",
  fromSurface: true,
});
const settingsScreenshotPath = resolve(import.meta.dir, "../../target/ui-settings-768x1024.png");
await Bun.write(settingsScreenshotPath, Buffer.from(settingsScreenshot.data, "base64"));
results.push({ view: "settings", ...settingsMetrics, screenshotPath: settingsScreenshotPath });

const rtlSelected = await client.evaluate<boolean>(`(() => {
  const select = document.querySelector('[data-testid="setting-language"]');
  if (!(select instanceof HTMLSelectElement)) return false;
  select.value = "ar";
  select.dispatchEvent(new Event("change", { bubbles: true }));
  return true;
})()`);
if (!rtlSelected) {
  throw new Error("RTL検証用の言語を選択できませんでした。");
}
await waitForCondition('document.documentElement.dir === "rtl"');
const rtlMetrics = await client.evaluate<Record<string, unknown>>(`({
  direction: document.documentElement.dir,
  language: document.documentElement.lang,
  documentOverflow: document.documentElement.scrollWidth > innerWidth,
  settingsTitle: document.querySelector(".modal-header h2")?.textContent
})`);
const rtlScreenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
  format: "png",
  fromSurface: true,
});
const rtlScreenshotPath = resolve(import.meta.dir, "../../target/ui-settings-rtl-768x1024.png");
await Bun.write(rtlScreenshotPath, Buffer.from(rtlScreenshot.data, "base64"));
results.push({ view: "settings-rtl", ...rtlMetrics, screenshotPath: rtlScreenshotPath });

await client.call("Page.addScriptToEvaluateOnNewDocument", {
  source: `(() => {
    const originalFetch = window.fetch.bind(window);
    window.__qaTranslationRequests = 0;
    window.__qaTranslationActive = 0;
    window.__qaTranslationMaximumActive = 0;
    window.__qaTranslationPostIds = [];
    window.__qaTranslationAttempts = {};
    window.fetch = (input, init) => {
      const raw = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      const url = new URL(raw, location.href);
      if (url.pathname === "/api/v1/accounts") {
        return Promise.resolve(Response.json([
          { accountId: "qa-account", userId: "42", username: "qa", displayName: "QA" }
        ]));
      }
      if (url.pathname === "/api/v1/trends") {
        return Promise.resolve(Response.json({
          trends: [
            { name: "#NyTweetDeck", description: "1,234 posts", rank: "1", url: "https://x.com/search?q=NyTweetDeck", domainContext: "Technology", metaDescription: "Trending now" },
            { name: "Japan", description: "2,345 posts", rank: "2", url: "https://x.com/search?q=Japan", domainContext: "News", metaDescription: "Trending in Japan" }
          ],
          nextCursor: null
        }));
      }
      if (url.pathname === "/api/v1/timelines/homeForYou") {
        return Promise.resolve(Response.json({
          posts: [{
            id: "100", text: "Initial engagement state", language: "en",
            createdAt: "2026-08-27T00:00:00Z",
            author: { id: "42", username: "qa", displayName: "QA", avatarUrl: null, verified: false },
            repostedBy: null, replyCount: 1, repostCount: 2, quoteCount: 0,
            likeCount: 3, bookmarkCount: 0, viewCount: 10,
            liked: true, reposted: true, bookmarked: false,
            replyToPostId: null, replyToUsername: null, quotedPost: null,
            communityNote: {
              title: "Community Note",
              text: "This image was taken in 2024.",
              footer: "Rated helpful by readers"
            },
            media: [{
              id: "video-qa", type: "video",
              url: "/api/v1/system/status",
              previewUrl: "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
            }]
          }, ...Array.from({ length: 12 }, (_, index) => ({
            id: "fresh-" + index, text: "Fresh original " + index, language: "en",
            createdAt: "2026-08-27T00:00:00Z",
            author: { id: "42", username: "qa", displayName: "QA", avatarUrl: null, verified: false },
            repostedBy: null, replyCount: 0, repostCount: 0, quoteCount: 0,
            likeCount: 0, bookmarkCount: 0, viewCount: 0,
            liked: false, reposted: false, bookmarked: false,
            replyToPostId: null, replyToUsername: null, quotedPost: null,
            communityNote: null, media: []
          }))],
          nextCursor: null
        }));
      }
      if (
        url.pathname.startsWith("/api/v1/posts/") &&
        url.pathname.endsWith("/translation")
      ) {
        const postId = decodeURIComponent(url.pathname.split("/")[4] ?? "unknown");
        window.__qaTranslationRequests += 1;
        window.__qaTranslationActive += 1;
        window.__qaTranslationMaximumActive = Math.max(
          window.__qaTranslationMaximumActive,
          window.__qaTranslationActive
        );
        window.__qaTranslationPostIds.push(postId);
        const attempt = (window.__qaTranslationAttempts[postId] ?? 0) + 1;
        window.__qaTranslationAttempts[postId] = attempt;
        return new Promise(resolve => setTimeout(() => {
          window.__qaTranslationActive -= 1;
          if (postId === "100" && attempt === 1) {
            resolve(new Response(null, { status: 429, headers: { "Retry-After": "0" } }));
            return;
          }
          resolve(Response.json({
            postId, sourceLanguage: "en", targetLanguage: "ja",
            text: "translated-" + postId, provider: "X"
          }));
        }, 40));
      }
      if (url.pathname === "/api/v1/notifications") {
        return Promise.resolve(Response.json({
          notifications: [{
            id: "community-qa", kind: "community_note",
            text: "Community Note added",
            noteId: "555", postId: null, imageUrls: []
          }],
          posts: [], nextCursor: null
        }));
      }
      if (url.pathname === "/api/v1/community-notes/555") {
        return Promise.resolve(Response.json({
          noteId: "555",
          text: "Complete note body with source",
          sources: [{ fromIndex: 24, toIndex: 30, url: "https://example.com/source" }],
          post: {
            id: "987", text: "Target post body", language: "ja",
            createdAt: "2026-08-27T00:00:00Z",
            author: { id: "42", username: "qa", displayName: "QA", avatarUrl: null, verified: false },
            repostedBy: null, replyCount: 1, repostCount: 2, quoteCount: 0,
            likeCount: 3, bookmarkCount: 0, viewCount: 10,
            liked: false, reposted: false, bookmarked: false,
            replyToPostId: null, replyToUsername: null, quotedPost: null,
            communityNote: null, media: []
          }
        }));
      }
      if (url.pathname.startsWith("/api/v1/live/subscriptions/")) {
        return Promise.resolve(Response.json({
          subscriptionId: "qa-subscription", connected: true, topicCount: 1
        }));
      }
      return originalFetch(input, init);
    };
  })();`,
});
await client.evaluate(`(() => {
  const stored = JSON.parse(localStorage.getItem("nytweetdeck.layout") ?? "null") ?? {};
  localStorage.setItem("nytweetdeck.layout", JSON.stringify({
    ...stored,
    version: 6,
    locale: "ja",
    activeAccountId: null,
    columns: [{ id: "qa-trends", kind: "trends", target: "", label: null }],
    trendSearchHistory: ["AI"]
  }));
})()`);
await reload();
await waitForCondition('document.querySelector("[data-testid=trend-filter-input]") !== null');
await waitForCondition('document.querySelectorAll(".trend-item").length === 2');
const trendFilterChanged = await client.evaluate<boolean>(`(() => {
  const input = document.querySelector("[data-testid=trend-filter-input]");
  if (!(input instanceof HTMLInputElement)) return false;
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")?.set;
  setter?.call(input, "technology");
  input.dispatchEvent(new Event("input", { bubbles: true }));
  input.closest("form")?.requestSubmit();
  return true;
})()`);
if (!trendFilterChanged) {
  throw new Error("トレンド絞り込み語句を入力できませんでした。");
}
await waitForCondition(
  'document.querySelectorAll(".trend-item").length === 1 && document.querySelector(".trend-item")?.textContent?.includes("#NyTweetDeck") === true',
);
await waitForCondition(
  'JSON.parse(localStorage.getItem("nytweetdeck.layout") ?? "{}").trendSearchHistory?.[0] === "technology"',
);
await reload();
await waitForCondition(
  'document.querySelector("[data-testid=trend-filter-input]")?.value === "technology"',
);
const trendMetrics = await client.evaluate<Record<string, unknown>>(`(() => {
  const input = document.querySelector("[data-testid=trend-filter-input]");
  const history = document.querySelector("datalist");
  const layout = JSON.parse(localStorage.getItem("nytweetdeck.layout") ?? "{}");
  return {
    filterValue: input instanceof HTMLInputElement ? input.value : null,
    historyValues: history instanceof HTMLDataListElement
      ? Array.from(history.options, option => option.value)
      : [],
    storedTarget: layout.columns?.[0]?.target,
    storedHistory: layout.trendSearchHistory,
    activeAccountId: layout.activeAccountId,
    layoutVersion: layout.version,
    visibleTrends: document.querySelectorAll(".trend-item").length,
    documentOverflow: document.documentElement.scrollWidth > innerWidth
  };
})()`);
const trendScreenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
  format: "png",
  fromSurface: true,
});
if (trendMetrics.layoutVersion !== 7) {
  throw new Error(`レイアウトv6からv7への移行に失敗しました: ${JSON.stringify(trendMetrics)}`);
}
if (trendMetrics.activeAccountId !== "qa-account") {
  throw new Error(`保存済み#1アカウントの自動選択に失敗しました: ${JSON.stringify(trendMetrics)}`);
}
const trendScreenshotPath = resolve(import.meta.dir, "../../target/ui-trend-filter-768x1024.png");
await Bun.write(trendScreenshotPath, Buffer.from(trendScreenshot.data, "base64"));
results.push({ view: "trend-filter", ...trendMetrics, screenshotPath: trendScreenshotPath });

await client.evaluate(`(() => {
  const layout = JSON.parse(localStorage.getItem("nytweetdeck.layout") ?? "{}");
  localStorage.setItem("nytweetdeck.layout", JSON.stringify({
    ...layout,
    locale: "ja",
    columns: [{ id: "qa-home", kind: "home", target: null, label: null }]
  }));
})()`);
await reload();
await waitForCondition('document.querySelector("[data-post-action=like]") !== null');
await waitForCondition('document.querySelector(".post-text")?.textContent === "translated-100"');
await waitForCondition("window.__qaTranslationActive === 0");
const engagementMetrics = await client.evaluate<Record<string, unknown>>(`(() => {
  const like = document.querySelector("[data-post-action=like]");
  const repost = document.querySelector("[data-post-action=repost]");
  const heart = like?.querySelector("svg");
  const video = document.querySelector(".post-media video");
  return {
    likeColor: like instanceof HTMLElement ? getComputedStyle(like).color : null,
    likeFilled: heart?.getAttribute("fill"),
    repostColor: repost instanceof HTMLElement ? getComputedStyle(repost).color : null,
    communityNoteText: document.querySelector("[data-testid=community-note-card]")?.textContent,
    videoLoop: video instanceof HTMLVideoElement ? video.loop : null,
    videoVolume: video instanceof HTMLVideoElement ? video.volume : null,
    videoMuted: video instanceof HTMLVideoElement ? video.muted : null,
    translationRequests: window.__qaTranslationRequests,
    translatedPostCount: new Set(window.__qaTranslationPostIds).size,
    translationMaximumActive: window.__qaTranslationMaximumActive,
    firstPostTranslationAttempts: window.__qaTranslationAttempts["100"] ?? 0,
    documentOverflow: document.documentElement.scrollWidth > innerWidth
  };
})()`);
if (
  engagementMetrics.likeColor !== "rgb(249, 24, 128)" ||
  engagementMetrics.likeFilled !== "currentColor" ||
  engagementMetrics.repostColor !== "rgb(0, 186, 124)" ||
  engagementMetrics.videoLoop !== true ||
  engagementMetrics.videoVolume !== 1 ||
  engagementMetrics.videoMuted !== true ||
  Number(engagementMetrics.translatedPostCount) >= 13 ||
  engagementMetrics.translationMaximumActive !== 2 ||
  engagementMetrics.firstPostTranslationAttempts !== 2
) {
  throw new Error(`反応済み色の検証に失敗しました: ${JSON.stringify(engagementMetrics)}`);
}
const engagementScreenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
  format: "png",
  fromSurface: true,
});
const engagementScreenshotPath = resolve(
  import.meta.dir,
  "../../target/ui-engagement-community-note-768x1024.png",
);
await Bun.write(engagementScreenshotPath, Buffer.from(engagementScreenshot.data, "base64"));
results.push({
  view: "engagement-community-note",
  ...engagementMetrics,
  screenshotPath: engagementScreenshotPath,
});

await client.evaluate(`(() => {
  const layout = JSON.parse(localStorage.getItem("nytweetdeck.layout") ?? "{}");
  localStorage.setItem("nytweetdeck.layout", JSON.stringify({
    ...layout,
    display: { ...layout.display, videoLoop: false, videoVolume: 35 }
  }));
})()`);
await reload();
await waitForCondition('document.querySelector(".post-media video") !== null');
await waitForCondition('document.querySelector(".post-media video")?.volume === 0.35');
const persistedVideoMetrics = await client.evaluate<Record<string, unknown>>(`(() => {
  const video = document.querySelector(".post-media video");
  const layout = JSON.parse(localStorage.getItem("nytweetdeck.layout") ?? "{}");
  return {
    videoLoop: video instanceof HTMLVideoElement ? video.loop : null,
    videoVolume: video instanceof HTMLVideoElement ? video.volume : null,
    storedLoop: layout.display?.videoLoop,
    storedVolume: layout.display?.videoVolume
  };
})()`);
if (
  persistedVideoMetrics.videoLoop !== false ||
  persistedVideoMetrics.videoVolume !== 0.35 ||
  persistedVideoMetrics.storedLoop !== false ||
  persistedVideoMetrics.storedVolume !== 35
) {
  throw new Error(`動画設定の永続化検証に失敗しました: ${JSON.stringify(persistedVideoMetrics)}`);
}
results.push({ view: "persisted-video-settings", ...persistedVideoMetrics });

await client.evaluate(`(() => {
  const layout = JSON.parse(localStorage.getItem("nytweetdeck.layout") ?? "{}");
  localStorage.setItem("nytweetdeck.layout", JSON.stringify({
    ...layout,
    columns: [{ id: "qa-notifications", kind: "notifications", target: null, label: null }]
  }));
})()`);
await reload();
await waitForCondition(
  'document.querySelector("[data-notification-kind=community_note]") !== null',
);
const communityNoteClicked = await client.evaluate<boolean>(`(() => {
  const notification = document.querySelector("[data-notification-kind=community_note]");
  if (!(notification instanceof HTMLButtonElement)) return false;
  notification.click();
  return true;
})()`);
if (!communityNoteClicked) {
  throw new Error("コミュニティノート通知を選択できませんでした。");
}
await waitForCondition('document.querySelector(".community-note-post-detail .post-card") !== null');
const communityNoteMetrics = await client.evaluate<Record<string, unknown>>(`({
  title: document.querySelector(".modal-header h2")?.textContent,
  postText: document.querySelector(".community-note-post-detail .post-text")?.textContent,
  noteText: document.querySelector(".community-note-post-detail .community-note-card")?.textContent,
  sourceCount: document.querySelectorAll(".community-note-post-detail .community-note-card a").length,
  documentOverflow: document.documentElement.scrollWidth > innerWidth
})`);
if (
  communityNoteMetrics.postText !== "Target post body" ||
  !String(communityNoteMetrics.noteText).includes("Complete note body with source") ||
  communityNoteMetrics.sourceCount !== 1
) {
  throw new Error(
    `コミュニティノート統合詳細の検証に失敗しました: ${JSON.stringify(communityNoteMetrics)}`,
  );
}
const communityNoteScreenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
  format: "png",
  fromSurface: true,
});
const communityNoteScreenshotPath = resolve(
  import.meta.dir,
  "../../target/ui-community-note-detail-768x1024.png",
);
await Bun.write(communityNoteScreenshotPath, Buffer.from(communityNoteScreenshot.data, "base64"));
results.push({
  view: "community-note-detail",
  ...communityNoteMetrics,
  screenshotPath: communityNoteScreenshotPath,
});

console.info(JSON.stringify(results, null, 2));
if (browserErrors.length > 0) {
  throw new Error(`ブラウザエラーを検出しました:\n${browserErrors.join("\n")}`);
}
client.close();
