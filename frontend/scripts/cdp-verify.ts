import { resolve } from "node:path";
import { CdpClient, type CdpTarget } from "./cdp-client";
import { readSharedLayout, updateSharedLayout } from "./shared-layout-cdp";

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
  const event = params as { entry?: { level?: string; text?: string; url?: string } };
  const expectedSettingsConflict =
    event.entry?.url?.endsWith("/api/v1/settings/layout") === true &&
    event.entry.text?.includes("status of 409") === true;
  if (event.entry?.level === "error" && !expectedSettingsConflict) {
    browserErrors.push(JSON.stringify(params));
  }
});

async function navigate(url = applicationUrl): Promise<void> {
  const loaded = client.waitForEvent("Page.loadEventFired");
  await client.call("Page.navigate", { url });
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
  const diagnostics = await client.evaluate<Record<string, unknown>>(`(() => ({
      columnCount: document.querySelectorAll(".deck-column").length,
      dialogCount: document.querySelectorAll('[role="dialog"]').length,
      addColumnButtonCount: document.querySelectorAll('[data-action="add-column"]').length,
      homeChoiceCount: document.querySelectorAll('[role="dialog"] [data-column-kind="home"]').length
  }))()`);
  throw new Error(
    `DOM状態の待機がタイムアウトしました: ${expression}; ${JSON.stringify(diagnostics)}`,
  );
}

await navigate();
await waitForCondition('document.querySelector(".app-shell") !== null');
await client.evaluate("localStorage.clear()");
await updateSharedLayout(client, "layout => ({ ...layout, columns: [], activeAccountId: null })");
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
  await updateSharedLayout(client, "layout => ({ ...layout, columns: [], activeAccountId: null })");
  await reload();
  await waitForCondition('document.querySelector("[data-action=add-column]") !== null');

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
  await waitForCondition(
    `(async () => (await (await fetch("/api/v1/settings/layout")).json()).layout.columns.length === ${viewport.columns})()`,
  );

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
  await waitForCondition(
    `document.querySelectorAll(".deck-column").length === ${viewport.columns}`,
  );
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
await waitForCondition('document.querySelector("[data-action=open-settings]") !== null');
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
const exportClicked = await client.evaluate<boolean>(`(() => {
  window.__qaExportedSettingsBlob = null;
  window.__qaExportedSettingsName = null;
  URL.createObjectURL = blob => {
    window.__qaExportedSettingsBlob = blob;
    return "blob:nytweetdeck-settings";
  };
  URL.revokeObjectURL = () => {};
  const originalClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function() {
    window.__qaExportedSettingsName = this.download;
  };
  const button = document.querySelector("[data-testid=export-settings]");
  if (!(button instanceof HTMLButtonElement)) return false;
  button.click();
  HTMLAnchorElement.prototype.click = originalClick;
  return true;
})()`);
if (!exportClicked) throw new Error("設定をエクスポートできませんでした。");
await waitForCondition("window.__qaExportedSettingsBlob instanceof Blob");
const exportedSettings = await client.evaluate<string>("window.__qaExportedSettingsBlob.text()");
const exportedSettingsName = await client.evaluate<string>("window.__qaExportedSettingsName");
const exportedDocument = JSON.parse(exportedSettings) as {
  format?: unknown;
  version?: unknown;
  layout?: { activeAccountId?: unknown; navItems?: unknown };
};
if (
  exportedDocument.format !== "NyTweetDeckSettings" ||
  exportedDocument.version !== 1 ||
  exportedDocument.layout?.activeAccountId !== null ||
  !exportedSettingsName.startsWith("NyTweetDeck-settings-")
) {
  throw new Error(`設定エクスポートが不正です: ${exportedSettingsName}`);
}
exportedDocument.layout = {
  ...exportedDocument.layout,
  activeAccountId: null,
  navItems: ["home", "trends"],
};
const importPayload = JSON.stringify(exportedDocument);
const importDispatched = await client.evaluate<boolean>(`(() => {
  const input = document.querySelector("[data-testid=import-settings]");
  if (!(input instanceof HTMLInputElement)) return false;
  const transfer = new DataTransfer();
  transfer.items.add(new File([${JSON.stringify(importPayload)}], "settings.json", {
    type: "application/json"
  }));
  input.files = transfer.files;
  input.dispatchEvent(new Event("change", { bubbles: true }));
  return true;
})()`);
if (!importDispatched) throw new Error("設定インポートを開始できませんでした。");
await waitForCondition('document.querySelector("[data-testid=settings-import-status]") !== null');
await waitForCondition(
  'document.querySelectorAll("[data-nav-item]").length === 2 && document.querySelector("[data-nav-item=home]") !== null && document.querySelector("[data-nav-item=trends]") !== null',
);
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
    settingsTransferFound: document.querySelector("[data-testid=layout-transfer-settings]") !== null,
    importedNavigation: Array.from(document.querySelectorAll("[data-nav-item]"), item => item.getAttribute("data-nav-item")),
    videoLoopChecked: document.querySelector("[data-testid=setting-video-loop]")?.checked,
    videoVolume: document.querySelector("[data-testid=setting-video-volume]")?.value
  };
})()`);
if (
  settingsMetrics.videoLoopChecked !== true ||
  settingsMetrics.videoVolume !== "100" ||
  settingsMetrics.translationHealthFound !== true ||
  settingsMetrics.settingsTransferFound !== true ||
  JSON.stringify(settingsMetrics.importedNavigation) !== JSON.stringify(["home", "trends"])
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
    window.__qaTimelineRequests = 0;
    window.__qaPostActionRequests = [];
    window.__qaResolvePostAction = null;
    window.__qaListRequests = 0;
    window.__qaListVersion = 0;
    window.fetch = (input, init) => {
      const raw = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      const url = new URL(raw, location.href);
      if (url.pathname === "/api/v1/accounts") {
        return Promise.resolve(Response.json([
          { accountId: "qa-account", userId: "42", username: "qa", displayName: "QA" }
        ]));
      }
      if (url.pathname === "/api/v1/lists") {
        window.__qaListRequests += 1;
        const source = url.searchParams.get("scope");
        const lists = source === "mine" ? [{
          id: window.__qaListVersion === 0 ? "84" : "85",
          name: window.__qaListVersion === 0 ? "Friends" : "Family",
          description: null, ownerName: "QA", ownerUsername: "qa",
          memberCount: 5, subscriberCount: 2, source: "mine"
        }] : [];
        return Promise.resolve(Response.json({ lists, nextCursor: null }));
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
        window.__qaTimelineRequests += 1;
        return Promise.resolve(Response.json({
          posts: [{
            id: "100", text: "Initial engagement state https://t.co/article100", language: "en",
            createdAt: "2026-08-27T00:00:00Z",
            author: { id: "42", username: "qa", displayName: "QA", avatarUrl: null, verified: false },
            repostedBy: null, replyCount: 1, repostCount: 2, quoteCount: 0,
            likeCount: 3, bookmarkCount: 0, viewCount: 10,
            liked: true, reposted: true, bookmarked: false,
            replyToPostId: null, replyToUsername: null,
            quotedPost: {
              id: "quoted-100", text: "Quoted original", language: "en",
              createdAt: "2026-08-27T00:00:00Z",
              author: { id: "43", username: "quoted", displayName: "Quoted", avatarUrl: null, verified: false },
              preTranslated: null, media: []
            },
            communityNote: {
              title: "Community Note",
              text: "This image was taken in 2024.",
              footer: "Rated helpful by readers"
            },
            article: {
              id: "article-100", title: "NyTweetDeck browser article",
              previewText: "A preview of the first lines shown inside the post card.",
              body: "First article paragraph.\\n\\nSecond paragraph with the complete article body.",
              coverImageUrl: "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==",
              url: "https://x.com/i/article/article-100"
            },
            media: [{
              id: "photo-qa", type: "photo",
              url: "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==",
              previewUrl: "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
            }, {
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
            communityNote: null,
            media: index === 5 ? [{
              id: "video-offscreen-qa", type: "video",
              url: "/api/v1/system/status",
              previewUrl: "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
            }] : []
          }))],
          nextCursor: null
        }));
      }
      if (url.pathname === "/api/v1/posts/100") {
        return Promise.resolve(Response.json({
          post: {
            id: "100", text: "Initial engagement state", language: "en",
            createdAt: "2026-08-27T00:00:00Z",
            author: { id: "42", username: "qa", displayName: "QA", avatarUrl: null, verified: false },
            repostedBy: null, replyCount: 1, repostCount: 2, quoteCount: 0,
            likeCount: 3, bookmarkCount: 0, viewCount: 10,
            liked: true, reposted: true, bookmarked: false,
            replyToPostId: null, replyToUsername: null, quotedPost: null,
            communityNote: null,
            article: {
              id: "article-100", title: "NyTweetDeck browser article",
              previewText: "A preview of the first lines shown inside the post card.",
              body: "First article paragraph.\\n\\nSecond paragraph with the complete article body.",
              coverImageUrl: "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==",
              url: "https://x.com/i/article/article-100"
            },
            media: [{
              id: "photo-qa", type: "photo",
              url: "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==",
              previewUrl: "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
            }]
          },
          replies: [], nextCursor: null
        }));
      }
      if (url.pathname.startsWith("/api/v1/posts/100/actions/")) {
        const action = url.pathname.split("/").at(-1) ?? "unknown";
        window.__qaPostActionRequests.push(action);
        return new Promise(resolve => {
          window.__qaResolvePostAction = status => resolve(new Response(null, { status }));
        });
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
await updateSharedLayout(
  client,
  `layout => ({
    ...layout,
    locale: "ja",
    activeAccountId: null,
    columns: [{ id: "qa-trends", kind: "trends", target: "", label: null }],
    trendSearchHistory: ["AI"]
  })`,
);
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
  '(async () => (await (await fetch("/api/v1/settings/layout")).json()).layout.trendSearchHistory?.[0] === "technology")()',
);
await reload();
await waitForCondition(
  'document.querySelector("[data-testid=trend-filter-input]")?.value === "technology"',
);
const storedTrendLayout = await readSharedLayout<Record<string, unknown>>(client);
const trendMetrics = await client.evaluate<Record<string, unknown>>(`(() => {
  const input = document.querySelector("[data-testid=trend-filter-input]");
  const history = document.querySelector("datalist");
  return {
    filterValue: input instanceof HTMLInputElement ? input.value : null,
    historyValues: history instanceof HTMLDataListElement
      ? Array.from(history.options, option => option.value)
      : [],
    visibleTrends: document.querySelectorAll(".trend-item").length,
    documentOverflow: document.documentElement.scrollWidth > innerWidth
  };
})()`);
Object.assign(trendMetrics, {
  storedTarget: (storedTrendLayout.columns as Array<{ target?: unknown }> | undefined)?.[0]?.target,
  storedHistory: storedTrendLayout.trendSearchHistory,
  activeAccountId: storedTrendLayout.activeAccountId,
  layoutVersion: storedTrendLayout.version,
});
const trendScreenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
  format: "png",
  fromSurface: true,
});
if (trendMetrics.layoutVersion !== 7) throw new Error("共有レイアウト版が不正です。");
if (trendMetrics.activeAccountId !== "qa-account") {
  throw new Error(`保存済み#1アカウントの自動選択に失敗しました: ${JSON.stringify(trendMetrics)}`);
}
const trendScreenshotPath = resolve(import.meta.dir, "../../target/ui-trend-filter-768x1024.png");
await Bun.write(trendScreenshotPath, Buffer.from(trendScreenshot.data, "base64"));
results.push({ view: "trend-filter", ...trendMetrics, screenshotPath: trendScreenshotPath });

await updateSharedLayout(
  client,
  `layout => ({
    ...layout,
    locale: "ja",
    columns: [{ id: "qa-home", kind: "home", target: null, label: null }],
    display: { ...layout.display, videoAutoplay: true }
  })`,
);
await reload();
await waitForCondition('document.querySelector("[data-post-action=like]") !== null');
await waitForCondition('document.querySelector(".post-text")?.textContent === "translated-100"');
await waitForCondition(
  'document.querySelector(".quoted-post-text")?.textContent === "translated-quoted-100"',
);
await waitForCondition("window.__qaTranslationActive === 0");
await waitForCondition("window.__qaListRequests === 2");
const addColumnOpened = await client.evaluate<boolean>(`(() => {
  const button = document.querySelector('[data-action="add-column"]');
  if (!(button instanceof HTMLButtonElement)) return false;
  button.click();
  return true;
})()`);
if (!addColumnOpened) throw new Error("リスト候補検証用のカラム追加画面を開けませんでした。");
await waitForCondition("document.querySelector('[data-column-kind=\"list\"]') !== null");
const listPickerOpened = await client.evaluate<boolean>(`(() => {
  const button = document.querySelector('[data-column-kind="list"]');
  if (!(button instanceof HTMLButtonElement)) return false;
  button.click();
  return true;
})()`);
if (!listPickerOpened) throw new Error("リスト候補画面を開けませんでした。");
await waitForCondition(`
  document.querySelector(".list-option")?.textContent?.includes("Friends") === true &&
  window.__qaListRequests === 2 &&
  document.querySelector(".column-target-form .primary-button")?.disabled === false
`);
await client.evaluate(`(() => {
  window.__qaListVersion = 1;
  window.dispatchEvent(new Event("focus"));
})()`);
await waitForCondition(`
  window.__qaListRequests === 4 &&
  document.querySelector(".list-option")?.textContent?.includes("Family") === true &&
  document.querySelector(".list-option")?.textContent?.includes("Friends") === false
`);
await client.evaluate('document.querySelector(".modal-header .icon-button")?.click()');
await waitForCondition('document.querySelector(".modal-panel") === null');
const optimisticLikeClicked = await client.evaluate<boolean>(`(() => {
  const button = document.querySelector("[data-post-action=like]");
  if (!(button instanceof HTMLButtonElement)) return false;
  button.click();
  return true;
})()`);
if (!optimisticLikeClicked) throw new Error("いいね解除を操作できませんでした。");
await waitForCondition(`
  window.__qaPostActionRequests.at(-1) === "unlike" &&
  document.querySelector("[data-post-action=like]")?.textContent === "2" &&
  document.querySelector("[data-post-action=like]")?.classList.contains("like-active") === false
`);
await client.evaluate("window.__qaResolvePostAction?.(503)");
await waitForCondition(`
  document.querySelector("[data-post-action=like]")?.textContent === "3" &&
  document.querySelector("[data-post-action=like]")?.classList.contains("like-active") === true &&
  document.querySelector(".post-action-error") !== null
`);
const optimisticRepostClicked = await client.evaluate<boolean>(`(() => {
  const menu = document.querySelector(".repost-menu");
  const confirm = menu?.querySelector("[data-post-action=repost-confirm]");
  if (!(menu instanceof HTMLDetailsElement) || !(confirm instanceof HTMLButtonElement)) return false;
  menu.open = true;
  confirm.click();
  return true;
})()`);
if (!optimisticRepostClicked) throw new Error("リポスト解除を操作できませんでした。");
await waitForCondition(`
  window.__qaPostActionRequests.at(-1) === "undoRepost" &&
  document.querySelector("[data-post-action=repost]")?.textContent === "1" &&
  document.querySelector("[data-post-action=repost]")?.classList.contains("repost-active") === false
`);
await client.evaluate("window.__qaResolvePostAction?.(503)");
await waitForCondition(`
  document.querySelector("[data-post-action=repost]")?.textContent === "2" &&
  document.querySelector("[data-post-action=repost]")?.classList.contains("repost-active") === true
`);
const timelineRequestsBeforeManualRefresh = await client.evaluate<number>(
  "window.__qaTimelineRequests",
);
const manualRefreshTriggered = await client.evaluate<boolean>(`(() => {
  const timeline = document.querySelector("[data-testid=timeline-scroll]");
  if (!(timeline instanceof HTMLElement)) return false;
  timeline.scrollTop = 0;
  timeline.dispatchEvent(new WheelEvent("wheel", { deltaY: -80, bubbles: true }));
  timeline.dispatchEvent(new WheelEvent("wheel", { deltaY: -40, bubbles: true }));
  return true;
})()`);
if (!manualRefreshTriggered) {
  throw new Error("タイムライン最上部で手動更新操作を実行できませんでした。");
}
await waitForCondition(
  `window.__qaTimelineRequests === ${timelineRequestsBeforeManualRefresh + 1}`,
);
const manualRefreshRequests = await client.evaluate<number>(
  `window.__qaTimelineRequests - ${timelineRequestsBeforeManualRefresh}`,
);
const quotedOriginalClicked = await client.evaluate<boolean>(`(() => {
  const button = document.querySelector(".quoted-post-translation-status button");
  if (!(button instanceof HTMLButtonElement)) return false;
  button.click();
  return true;
})()`);
if (!quotedOriginalClicked) {
  throw new Error("引用元の原文切替を操作できませんでした。");
}
await waitForCondition(
  'document.querySelector(".quoted-post-text")?.textContent === "Quoted original"',
);
await waitForCondition('document.querySelectorAll(".post-media video").length === 2');
const firstVideoPositioned = await client.evaluate<boolean>(`(() => {
  const video = document.querySelector('[data-media-id="video-qa"]');
  if (!(video instanceof HTMLVideoElement)) return false;
  video.scrollIntoView({ block: "center" });
  return true;
})()`);
if (!firstVideoPositioned) throw new Error("先頭動画を再生帯へ移動できませんでした。");
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-qa"]\')?.getAttribute("src") !== null',
);
const deferredVideoMetrics = await client.evaluate<Record<string, unknown>>(`(() => {
  const active = document.querySelector('[data-media-id="video-qa"]');
  const deferred = document.querySelector('[data-media-id="video-offscreen-qa"]');
  return {
    activeSource: active?.getAttribute("src"),
    activeAutoplay: active instanceof HTMLVideoElement ? active.autoplay : null,
    activeInPlaybackZone: active?.getAttribute("data-viewport-active"),
    deferredSource: deferred?.getAttribute("src"),
    deferredAutoplay: deferred instanceof HTMLVideoElement ? deferred.autoplay : null,
    deferredInPlaybackZone: deferred?.getAttribute("data-viewport-active"),
    deferredTop: deferred instanceof Element ? deferred.getBoundingClientRect().top : null,
    playbackZoneBottom: innerHeight / 2
  };
})()`);
if (
  deferredVideoMetrics.activeSource === null ||
  deferredVideoMetrics.activeAutoplay !== true ||
  deferredVideoMetrics.activeInPlaybackZone !== "true" ||
  deferredVideoMetrics.deferredSource !== null ||
  deferredVideoMetrics.deferredAutoplay !== false ||
  deferredVideoMetrics.deferredInPlaybackZone !== "false" ||
  Number(deferredVideoMetrics.deferredTop) <= Number(deferredVideoMetrics.playbackZoneBottom)
) {
  throw new Error(
    `画面外動画の遅延ロード検証に失敗しました: ${JSON.stringify(deferredVideoMetrics)}`,
  );
}
const deferredVideoPositioned = await client.evaluate<boolean>(`(() => {
  const video = document.querySelector('[data-media-id="video-offscreen-qa"]');
  if (!(video instanceof HTMLVideoElement)) return false;
  video.scrollIntoView({ block: "center" });
  return true;
})()`);
if (!deferredVideoPositioned) throw new Error("遅延動画を再生帯へ移動できませんでした。");
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-offscreen-qa"]\')?.getAttribute("src") !== null',
);
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-offscreen-qa"]\')?.getAttribute("data-viewport-active") === "true"',
);
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-qa"]\')?.getAttribute("data-viewport-active") === "false"',
);
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-qa"]\')?.getAttribute("src") === null',
);
const replayVideoPositioned = await client.evaluate<boolean>(`(() => {
  const video = document.querySelector('[data-media-id="video-qa"]');
  if (!(video instanceof HTMLVideoElement)) return false;
  video.scrollIntoView({ block: "center" });
  return true;
})()`);
if (!replayVideoPositioned) throw new Error("先頭動画を再生帯へ戻せませんでした。");
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-qa"]\')?.getAttribute("data-viewport-active") === "true"',
);
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-offscreen-qa"]\')?.getAttribute("src") === null',
);
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
    videoAutoplay: video instanceof HTMLVideoElement ? video.autoplay : null,
    videoInPlaybackZone: video?.getAttribute("data-viewport-active"),
    translationRequests: window.__qaTranslationRequests,
    translatedPostCount: new Set(window.__qaTranslationPostIds).size,
    translationMaximumActive: window.__qaTranslationMaximumActive,
    firstPostTranslationAttempts: window.__qaTranslationAttempts["100"] ?? 0,
    manualRefreshRequests: ${manualRefreshRequests},
    quotedPostText: document.querySelector(".quoted-post-text")?.textContent,
    quotedTranslationProvider: document.querySelector(".quoted-post-translation-status")?.textContent,
    quoteToggleOpenedDialog: document.querySelector('[role="dialog"]') !== null,
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
  engagementMetrics.videoAutoplay !== true ||
  engagementMetrics.videoInPlaybackZone !== "true" ||
  Number(engagementMetrics.translatedPostCount) >= 14 ||
  engagementMetrics.translationMaximumActive !== 2 ||
  engagementMetrics.firstPostTranslationAttempts !== 2 ||
  engagementMetrics.manualRefreshRequests !== 1 ||
  engagementMetrics.quotedPostText !== "Quoted original" ||
  !String(engagementMetrics.quotedTranslationProvider).includes("Xによる自動翻訳") ||
  engagementMetrics.quoteToggleOpenedDialog !== false
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
  ...deferredVideoMetrics,
  ...engagementMetrics,
  screenshotPath: engagementScreenshotPath,
});

const articleOpened = await client.evaluate<boolean>(`(() => {
  const article = document.querySelector(".x-article-card");
  if (!(article instanceof HTMLButtonElement)) return false;
  article.scrollIntoView({ block: "center" });
  article.click();
  return true;
})()`);
if (!articleOpened) throw new Error("X記事カードを開けませんでした。");
await waitForCondition(
  'document.querySelector(".article-reader-body")?.textContent?.includes("complete article body") === true',
);
const articleMetrics = await client.evaluate<Record<string, unknown>>(`({
  title: document.querySelector(".article-reader h2")?.textContent,
  body: document.querySelector(".article-reader-body")?.textContent,
  coverFound: document.querySelector(".article-reader-cover") !== null,
  documentOverflow: document.documentElement.scrollWidth > innerWidth
})`);
const articleScreenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
  format: "png",
  fromSurface: true,
});
const articleScreenshotPath = resolve(
  import.meta.dir,
  "../../target/ui-article-reader-768x1024.png",
);
await Bun.write(articleScreenshotPath, Buffer.from(articleScreenshot.data, "base64"));
await client.evaluate(
  'document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }))',
);
await waitForCondition('document.querySelector(".article-reader") === null');
results.push({ view: "article-reader", ...articleMetrics, screenshotPath: articleScreenshotPath });

const postDetailOpened = await client.evaluate<boolean>(`(() => {
  const post = document.querySelector('[data-post-id="100"] .post-open-button');
  if (!(post instanceof HTMLButtonElement)) return false;
  post.click();
  return true;
})()`);
if (!postDetailOpened) throw new Error("画像付きポスト詳細を開けませんでした。");
await waitForCondition('document.querySelector(".post-detail-content .post-image-open") !== null');
const imageOpened = await client.evaluate<boolean>(`(() => {
  const image = document.querySelector(".post-detail-content .post-image-open");
  if (!(image instanceof HTMLButtonElement)) return false;
  image.click();
  return true;
})()`);
if (!imageOpened) throw new Error("フルサイズ画像を開けませんでした。");
await waitForCondition('document.querySelector(".image-viewer-viewport") !== null');
const imageMoved = await client.evaluate<boolean>(`(() => {
  const viewport = document.querySelector(".image-viewer-viewport");
  if (!(viewport instanceof HTMLElement)) return false;
  viewport.dispatchEvent(new WheelEvent("wheel", { deltaY: -240, clientX: 200, clientY: 300, bubbles: true }));
  viewport.dispatchEvent(new PointerEvent("pointerdown", { pointerId: 1, button: 0, clientX: 100, clientY: 100, bubbles: true }));
  viewport.dispatchEvent(new PointerEvent("pointermove", { pointerId: 1, clientX: 150, clientY: 135, bubbles: true }));
  viewport.dispatchEvent(new PointerEvent("pointerup", { pointerId: 1, clientX: 150, clientY: 135, bubbles: true }));
  return true;
})()`);
if (!imageMoved) throw new Error("画像の拡大・移動操作を実行できませんでした。");
await waitForCondition(
  'Number(document.querySelector(".image-viewer-viewport")?.getAttribute("data-zoom")) > 1',
);
const imageViewerMetrics = await client.evaluate<Record<string, unknown>>(`({
  zoom: document.querySelector(".image-viewer-viewport")?.getAttribute("data-zoom"),
  transform: document.querySelector(".image-viewer-viewport img")?.style.transform,
  detailBehindViewer: document.querySelector(".post-detail-content") !== null,
  documentOverflow: document.documentElement.scrollWidth > innerWidth
})`);
const imageScreenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
  format: "png",
  fromSurface: true,
});
const imageScreenshotPath = resolve(import.meta.dir, "../../target/ui-image-viewer-768x1024.png");
await Bun.write(imageScreenshotPath, Buffer.from(imageScreenshot.data, "base64"));
await client.evaluate(
  'document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }))',
);
await waitForCondition(
  'document.querySelector(".image-viewer") === null && document.querySelector(".post-detail-content") !== null',
);
await client.evaluate(
  'document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }))',
);
await waitForCondition('document.querySelector(".post-detail-content") === null');
results.push({ view: "image-viewer", ...imageViewerMetrics, screenshotPath: imageScreenshotPath });

await updateSharedLayout(
  client,
  `layout => ({
    ...layout,
    display: { ...layout.display, videoLoop: false, videoVolume: 35 }
  })`,
);
await reload();
await waitForCondition('document.querySelector(".post-media video") !== null');
await waitForCondition('document.querySelector(".post-media video")?.volume === 0.35');
const persistedVideoMetrics = await client.evaluate<Record<string, unknown>>(`(() => {
  const video = document.querySelector(".post-media video");
  return {
    videoLoop: video instanceof HTMLVideoElement ? video.loop : null,
    videoVolume: video instanceof HTMLVideoElement ? video.volume : null
  };
})()`);
const storedVideoLayout = await readSharedLayout<{ display?: Record<string, unknown> }>(client);
persistedVideoMetrics.storedLoop = storedVideoLayout.display?.videoLoop;
persistedVideoMetrics.storedVolume = storedVideoLayout.display?.videoVolume;
if (
  persistedVideoMetrics.videoLoop !== false ||
  persistedVideoMetrics.videoVolume !== 0.35 ||
  persistedVideoMetrics.storedLoop !== false ||
  persistedVideoMetrics.storedVolume !== 35
) {
  throw new Error(`動画設定の永続化検証に失敗しました: ${JSON.stringify(persistedVideoMetrics)}`);
}
results.push({ view: "persisted-video-settings", ...persistedVideoMetrics });

await updateSharedLayout(
  client,
  `layout => ({
    ...layout,
    columns: [{ id: "qa-notifications", kind: "notifications", target: null, label: null }]
  })`,
);
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

const alternateUrl = applicationUrl.replace("127.0.0.1", "localhost");
await navigate(alternateUrl);
await waitForCondition('document.querySelector("[data-column-kind=notifications]") !== null');
const alternateLayout = await readSharedLayout<{ display?: { videoVolume?: unknown } }>(client);
if (alternateLayout.display?.videoVolume !== 35)
  throw new Error("アドレス間設定共有に失敗しました。");
results.push({ view: "address-independent-settings", alternateUrl, videoVolume: 35 });

console.info(JSON.stringify(results, null, 2));
if (browserErrors.length > 0) {
  throw new Error(`ブラウザエラーを検出しました:\n${browserErrors.join("\n")}`);
}
client.close();
