import { installUpdateFixture, verifyUpdateButton } from "./update-button-cdp";
import { resolve } from "node:path";
import {
  CdpClient,
  type CdpTarget,
  navigatePage,
  reloadPage,
  waitForPageCondition,
} from "./cdp-client";
import { readSharedLayout, updateSharedLayout } from "./shared-layout-cdp";
import { layoutVersion } from "../src/model/layout";

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

const navigate = (url = applicationUrl) => navigatePage(client, url);
const reload = () => reloadPage(client);
const waitForCondition = (expression: string, timeoutMilliseconds?: number) =>
  waitForPageCondition(client, expression, timeoutMilliseconds);

await installUpdateFixture(client);
await navigate();
await waitForCondition('document.querySelector(".app-shell") !== null');
await client.evaluate("localStorage.clear()");
await updateSharedLayout(
  client,
  "layout => ({ ...layout, columns: [], activeAccountId: null, replySort: 'relevance', display: { ...layout.display, videoLoop: true, videoVolume: 100 } })",
);
await reload();

const results: Array<Record<string, unknown>> = [];

await client.call("Emulation.setDeviceMetricsOverride", {
  width: 1_440,
  height: 900,
  deviceScaleFactor: 1,
  mobile: false,
});
await updateSharedLayout(
  client,
  "layout => ({ ...layout, display: { ...layout.display, showMainNavigation: false } })",
);
await reload();
await waitForCondition('document.querySelector("[data-testid=show-main-navigation]") !== null');
await client.call("Input.dispatchMouseEvent", {
  type: "mouseMoved",
  x: 1,
  y: 300,
});
await waitForCondition('document.querySelector(".main-navigation") !== null');
await client.call("Input.dispatchMouseEvent", {
  type: "mouseMoved",
  x: 32,
  y: 300,
});
await Bun.sleep(3_200);
const navigationHeldWhileHovered = await client.evaluate<boolean>(
  'document.querySelector(".main-navigation") !== null',
);
if (!navigationHeldWhileHovered) {
  throw new Error("メインナビゲーションがホバー中に自動収納されました。");
}
await client.call("Input.dispatchMouseEvent", {
  type: "mouseMoved",
  x: 180,
  y: 300,
});
await waitForCondition('document.querySelector(".main-navigation") === null', 4_500);
results.push({
  view: "auto-hide-navigation",
  revealedFromEdge: true,
  heldWhileHovered: navigationHeldWhileHovered,
  hiddenAfterPointerExit: true,
});
await updateSharedLayout(
  client,
  "layout => ({ ...layout, display: { ...layout.display, showMainNavigation: true } })",
);
await reload();
await Bun.sleep(3_200);
if (!(await client.evaluate<boolean>('document.querySelector(".main-navigation") !== null'))) {
  throw new Error("メインナビゲーションの常時表示設定が維持されませんでした。");
}

const viewports = [
  { width: 1440, height: 900, columns: 3 },
  { width: 768, height: 1024, columns: 2 },
  { width: 390, height: 844, columns: 1 },
] as const;

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

  const metrics = await client.evaluate<Record<string, unknown>>(`(() => {
    const navigation = document.querySelector(".main-navigation")?.getBoundingClientRect();
    const columns = Array.from(document.querySelectorAll(".deck-column"), element => element.getBoundingClientRect());
    return {
      viewport: { width: innerWidth, height: innerHeight },
      documentWidth: document.documentElement.scrollWidth,
      bodyWidth: document.body.getBoundingClientRect().width,
      columnCount: columns.length,
      dialogCount: document.querySelectorAll('[role="dialog"]').length,
      horizontalOverflow: document.documentElement.scrollWidth > innerWidth,
      interactiveElements: document.querySelectorAll("button, select, a, input").length,
      navigationToFirstColumnGap: navigation && columns[0] ? columns[0].left - navigation.right : null,
      firstToSecondColumnGap: columns[0] && columns[1] ? columns[1].left - columns[0].right : null
    };
  })()`);
  if (
    typeof metrics.firstToSecondColumnGap === "number" &&
    (typeof metrics.navigationToFirstColumnGap !== "number" ||
      Math.abs(metrics.navigationToFirstColumnGap - metrics.firstToSecondColumnGap) > 0.5)
  ) {
    throw new Error(
      `メニューと第1カラムの間隔がカラム間隔と一致しません: ${JSON.stringify(metrics)}`,
    );
  }

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
    desktopUpdateFound: document.querySelector("[data-testid=desktop-update-settings]") !== null,
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
  settingsMetrics.desktopUpdateFound !== true ||
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
    window.__qaTimelineCursors = [];
    window.__qaTimelineVersion = 0;
    window.__qaReplySortRequests = [];
    window.__qaHoldTimeline = false;
    window.__qaResolveTimeline = null;
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
        const timelineCursor = url.searchParams.get("cursor");
        window.__qaTimelineCursors.push(timelineCursor);
        const response = timelineCursor === null ? Response.json({
          posts: [...(window.__qaTimelineVersion === 0 ? [] : Array.from({ length: 6 }, (_, index) => ({
            id: "new-" + index, text: "New post " + index, language: "ja",
            createdAt: "2026-08-28T00:00:00Z",
            author: {
              id: "new-author-" + index,
              username: "new_author_" + index,
              displayName: "New Author " + index,
              avatarUrl: index % 2 === 0
                ? "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
                : null,
              verified: false
            },
            repostedBy: null, replyCount: 0, repostCount: 0, quoteCount: 0,
            likeCount: 0, bookmarkCount: 0, viewCount: 0,
            liked: false, reposted: false, bookmarked: false,
            replyToPostId: null, replyToUsername: null, quotedPost: null,
            communityNote: null, media: []
          }))), {
            id: "100", text: "Initial engagement state https://t.co/article100", language: "en",
            createdAt: "2026-08-27T00:00:00Z",
            author: { id: "42", username: "qa", displayName: "QA", avatarUrl: null, verified: false },
            repostedBy: null, replyCount: 1, repostCount: 2, quoteCount: 0,
            likeCount: 3, bookmarkCount: 1, viewCount: 10,
            liked: true, reposted: true, bookmarked: true,
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
          nextCursor: "cache-overflow-page"
        }) : Response.json({
          posts: Array.from({ length: 188 }, (_, index) => ({
            id: "older-" + index, text: "Older cached post " + index, language: "ja",
            createdAt: "2026-08-26T00:00:00Z",
            author: { id: "42", username: "qa", displayName: "QA", avatarUrl: null, verified: false },
            repostedBy: null, replyCount: 0, repostCount: 0, quoteCount: 0,
            likeCount: 0, bookmarkCount: 0, viewCount: 0,
            liked: false, reposted: false, bookmarked: false,
            replyToPostId: null, replyToUsername: null, quotedPost: null,
            communityNote: null, media: []
          })),
          nextCursor: "cursor-after-overflow"
        });
        if (window.__qaHoldTimeline && timelineCursor === null) {
          return new Promise(resolve => {
            window.__qaResolveTimeline = () => {
              window.__qaHoldTimeline = false;
              window.__qaResolveTimeline = null;
              resolve(response);
            };
          });
        }
        return Promise.resolve(response);
      }
      if (url.pathname === "/api/v1/posts/100") {
        const replySort = url.searchParams.get("replySort") ?? "relevance";
        window.__qaReplySortRequests.push(replySort);
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
            }, {
              id: "photo-qa-next", type: "photo",
              url: "data:image/gif;base64,R0lGODlhAQABAIAAAAD/AP///ywAAAAAAQABAAACAUwAOw==",
              previewUrl: "data:image/gif;base64,R0lGODlhAQABAIAAAAD/AP///ywAAAAAAQABAAACAUwAOw=="
            }]
          },
          replies: [{
            id: "reply-regular", text: replySort + " regular reply", language: "ja",
            createdAt: "2026-08-28T00:00:00Z",
            author: { id: "51", username: "regular", displayName: "Regular", avatarUrl: null, verified: false },
            repostedBy: null, conversationSection: "HighQuality",
            replyCount: 0, repostCount: 0, quoteCount: 0, likeCount: 2,
            bookmarkCount: 0, viewCount: 4, liked: false, reposted: false, bookmarked: false,
            replyToPostId: "100", replyToUsername: "qa", quotedPost: null, media: []
          }, {
            id: "reply-low", text: "low quality reply", language: "ja",
            createdAt: "2026-08-28T00:00:00Z",
            author: { id: "52", username: "low", displayName: "Low", avatarUrl: null, verified: false },
            repostedBy: null, conversationSection: "LowQuality",
            replyCount: 0, repostCount: 0, quoteCount: 0, likeCount: 0,
            bookmarkCount: 0, viewCount: 1, liked: false, reposted: false, bookmarked: false,
            replyToPostId: "reply-regular", replyToUsername: "regular", quotedPost: null, media: []
          }, {
            id: "reply-abusive", text: "abusive quality reply", language: "ja",
            createdAt: "2026-08-28T00:00:00Z",
            author: { id: "53", username: "abusive", displayName: "Abusive", avatarUrl: null, verified: false },
            repostedBy: null, conversationSection: "AbusiveQuality",
            replyCount: 0, repostCount: 0, quoteCount: 0, likeCount: 0,
            bookmarkCount: 0, viewCount: 1, liked: false, reposted: false, bookmarked: false,
            replyToPostId: "reply-low", replyToUsername: "low", quotedPost: null, media: []
          }], nextCursor: null
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
          notifications: [
            {
              id: "follow-qa", kind: "follow", text: "Alice and Bob followed you",
              noteId: null, postId: null,
              actors: [
                { id: "42", username: "alice", displayName: "Alice", avatarUrl: null },
                { id: "84", username: "bob", displayName: "Bob", avatarUrl: null }
              ],
              imageUrls: []
            },
            {
              id: "community-qa", kind: "community_note",
              text: "Community Note added",
              noteId: "555", postId: null, actors: [], imageUrls: []
            }
          ],
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
if (trendMetrics.layoutVersion !== layoutVersion) throw new Error("共有レイアウト版が不正です。");
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
const overflowPageRequested = await client.evaluate<boolean>(`(() => {
  if (window.__qaTimelineCursors.filter(cursor => cursor === "cache-overflow-page").length > 0) {
    return true;
  }
  const loadMore = document.querySelector(".load-more-button");
  if (!(loadMore instanceof HTMLButtonElement)) return false;
  loadMore.click();
  return true;
})()`);
if (!overflowPageRequested) throw new Error("200件上限を超える追加ページを読み込めませんでした。");
await waitForCondition(`
  window.__qaTimelineCursors.filter(cursor => cursor === "cache-overflow-page").length === 1 &&
  document.querySelectorAll(".post-card").length === 201
`);
const cachedTimelineReopened = await client.evaluate<boolean>(`(() => {
  window.__qaHoldTimeline = true;
  const remove = document.querySelector('[data-action="remove-column"]');
  if (!(remove instanceof HTMLButtonElement)) return false;
  remove.click();
  return true;
})()`);
if (!cachedTimelineReopened) throw new Error("キャッシュ検証用カラムを閉じられませんでした。");
await waitForCondition('document.querySelector("[data-testid=timeline-scroll]") === null');
await client.evaluate("document.querySelector('[data-action=\"add-column\"]')?.click()");
await waitForCondition("document.querySelector('[data-column-kind=\"home\"]') !== null");
await client.evaluate("document.querySelector('[data-column-kind=\"home\"]')?.click()");
await waitForCondition(`
  document.querySelector("[data-testid=timeline-scroll]") !== null &&
  document.querySelector(".post-text")?.textContent === "translated-100" &&
  window.__qaResolveTimeline !== null
`);
const cachedPostsBeforeRevalidation = await client.evaluate<number>(
  'document.querySelectorAll(".post-card").length',
);
if (cachedPostsBeforeRevalidation !== 13) {
  throw new Error(`再表示キャッシュの投稿件数が不正です: ${cachedPostsBeforeRevalidation}/13`);
}
const cachedTimelineMarkerSet = await client.evaluate<boolean>(`(() => {
  const post = document.querySelector(".post-card");
  if (!(post instanceof HTMLElement)) return false;
  post.dataset.cacheMarker = "retained";
  return document.querySelector(".column-message") === null;
})()`);
if (!cachedTimelineMarkerSet)
  throw new Error("キャッシュ済みタイムラインを即時表示できませんでした。");
await client.evaluate("window.__qaResolveTimeline?.()");
await waitForCondition(`
  window.__qaResolveTimeline === null &&
  document.querySelector(".post-card")?.getAttribute("data-cache-marker") === "retained"
`);
const cachedContinuationRequested = await client.evaluate<boolean>(`(() => {
  const loadMore = document.querySelector(".load-more-button");
  if (!(loadMore instanceof HTMLButtonElement)) return false;
  loadMore.click();
  return true;
})()`);
if (!cachedContinuationRequested)
  throw new Error("再表示したキャッシュの続きを読み込めませんでした。");
await waitForCondition(`
  window.__qaTimelineCursors.filter(cursor => cursor === "cache-overflow-page").length === 2 &&
  window.__qaTimelineCursors.at(-1) === "cache-overflow-page" &&
  document.querySelectorAll(".post-card").length === 201
`);
results.push({
  view: "timeline-cache-continuation",
  cachedPosts: cachedPostsBeforeRevalidation,
  reopenedPostsAfterContinuation: 201,
  resumedCursor: "cache-overflow-page",
});
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
  document.querySelector("[data-post-action=like]")?.classList.contains("like-active") === false &&
  document.querySelector("[data-post-action=like]")?.disabled === false &&
  document.querySelector("[data-post-action=like]")?.getAttribute("aria-busy") === "true"
`);
await client.evaluate("window.__qaResolvePostAction?.(503)");
await waitForCondition(`
  document.querySelector("[data-post-action=like]")?.textContent === "3" &&
  document.querySelector("[data-post-action=like]")?.classList.contains("like-active") === true &&
  document.querySelector(".post-action-error") !== null
`);
const optimisticRepostOpened = await client.evaluate<boolean>(`(() => {
  const menu = document.querySelector(".repost-menu");
  const trigger = menu?.querySelector("[data-post-action=repost]");
  if (!(menu instanceof HTMLElement) || !(trigger instanceof HTMLButtonElement)) return false;
  trigger.click();
  return true;
})()`);
if (!optimisticRepostOpened) throw new Error("リポストメニューを操作できませんでした。");
await waitForCondition(`
  document.querySelector("[data-post-action=repost]")?.getAttribute("aria-expanded") === "true" &&
  document.querySelector(".repost-menu [role=menu]") !== null
`);
const optimisticRepostClicked = await client.evaluate<boolean>(`(() => {
  const confirm = document.querySelector("[data-post-action=repost-confirm]");
  if (!(confirm instanceof HTMLButtonElement)) return false;
  confirm.click();
  return true;
})()`);
if (!optimisticRepostClicked) throw new Error("リポスト解除を操作できませんでした。");
await waitForCondition(`
  window.__qaPostActionRequests.at(-1) === "undoRepost" &&
  document.querySelector("[data-post-action=repost]")?.textContent === "1" &&
  document.querySelector("[data-post-action=repost]")?.classList.contains("repost-active") === false &&
  document.querySelector("[data-post-action=repost]")?.getAttribute("aria-busy") === "true"
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
await waitForCondition('document.querySelectorAll(".configured-video").length === 2');
const firstVideoPositioned = await client.evaluate<boolean>(`(() => {
  const player = document.querySelector('[data-media-id="video-qa"]');
  if (!(player instanceof HTMLElement)) return false;
  player.scrollIntoView({ block: "start" });
  return true;
})()`);
if (!firstVideoPositioned) throw new Error("先頭動画を再生帯へ移動できませんでした。");
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-qa"]\')?.querySelector("video") !== null',
);
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-qa"]\')?.getAttribute("data-viewport-active") === "true"',
);
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-offscreen-qa"]\')?.getAttribute("data-media-connected") === "true"',
);
await client.evaluate(
  '(() => { window.__qaFirstVideoElement = document.querySelector(\'[data-media-id="video-qa"]\')?.querySelector("video"); return true; })()',
);
const deferredVideoMetrics = await client.evaluate<Record<string, unknown>>(`(() => {
  const active = document.querySelector('[data-media-id="video-qa"]');
  const deferred = document.querySelector('[data-media-id="video-offscreen-qa"]');
  const activeVideo = active?.querySelector("video");
  const deferredVideo = deferred?.querySelector("video");
  return {
    activeSource: activeVideo?.getAttribute("src"),
    activeAutoplay: activeVideo instanceof HTMLVideoElement ? activeVideo.autoplay : null,
    activeInPlaybackZone: active?.getAttribute("data-viewport-active"),
    activeNativeControls: activeVideo instanceof HTMLVideoElement ? activeVideo.controls : null,
    activeControlButtons: active?.querySelectorAll(".configured-video-controls button").length,
    activeControlSliders: active?.querySelectorAll('.configured-video-controls input[type="range"]').length,
    deferredVideoConnected: deferredVideo !== null,
    deferredMediaConnected: deferred?.getAttribute("data-media-connected"),
    deferredInPlaybackZone: deferred?.getAttribute("data-viewport-active"),
    deferredTop: deferred instanceof Element ? deferred.getBoundingClientRect().top : null,
    playbackZoneBottom: innerHeight / 2,
    preloadZoneBottom: innerHeight + 1200
  };
})()`);
if (
  deferredVideoMetrics.activeSource === null ||
  deferredVideoMetrics.activeAutoplay !== true ||
  deferredVideoMetrics.activeInPlaybackZone !== "true" ||
  deferredVideoMetrics.activeNativeControls !== false ||
  Number(deferredVideoMetrics.activeControlButtons) < 4 ||
  deferredVideoMetrics.activeControlSliders !== 2 ||
  deferredVideoMetrics.deferredVideoConnected !== true ||
  deferredVideoMetrics.deferredMediaConnected !== "true" ||
  deferredVideoMetrics.deferredInPlaybackZone !== "false" ||
  Number(deferredVideoMetrics.deferredTop) <= Number(deferredVideoMetrics.playbackZoneBottom) ||
  Number(deferredVideoMetrics.deferredTop) > Number(deferredVideoMetrics.preloadZoneBottom)
) {
  throw new Error(`画面外動画の先読み検証に失敗しました: ${JSON.stringify(deferredVideoMetrics)}`);
}
const deferredVideoPositioned = await client.evaluate<boolean>(`(() => {
  const player = document.querySelector('[data-media-id="video-offscreen-qa"]');
  if (!(player instanceof HTMLElement)) return false;
  player.scrollIntoView({ block: "start" });
  return true;
})()`);
if (!deferredVideoPositioned) throw new Error("遅延動画を再生帯へ移動できませんでした。");
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-offscreen-qa"]\')?.querySelector("video") !== null',
);
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-offscreen-qa"]\')?.getAttribute("data-viewport-active") === "true"',
);
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-qa"]\')?.getAttribute("data-viewport-active") === "false"',
);
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-qa"]\')?.querySelector("video") === null',
);
const replayVideoPositioned = await client.evaluate<boolean>(`(() => {
  const player = document.querySelector('[data-media-id="video-qa"]');
  if (!(player instanceof HTMLElement)) return false;
  player.scrollIntoView({ block: "start" });
  return true;
})()`);
if (!replayVideoPositioned) throw new Error("先頭動画を再生帯へ戻せませんでした。");
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-qa"]\')?.getAttribute("data-viewport-active") === "true"',
);
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-qa"]\')?.querySelector("video") !== window.__qaFirstVideoElement',
);
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-offscreen-qa"]\')?.getAttribute("data-media-connected") === "true"',
);
await waitForCondition(
  'document.querySelector(\'[data-media-id="video-offscreen-qa"]\')?.getAttribute("data-viewport-active") === "false"',
);
const engagementMetrics = await client.evaluate<Record<string, unknown>>(`(() => {
  const like = document.querySelector("[data-post-action=like]");
  const repost = document.querySelector("[data-post-action=repost]");
  const bookmark = document.querySelector("[data-post-action=bookmark]");
  const heart = like?.querySelector("svg");
  const video = document.querySelector(".post-media video");
  const player = video?.closest(".configured-video");
  const videoDragTransfer = new DataTransfer();
  const videoDragAllowed = video instanceof HTMLVideoElement
    ? video.dispatchEvent(new DragEvent("dragstart", {
        bubbles: true,
        cancelable: true,
        dataTransfer: videoDragTransfer
      }))
    : null;
  return {
    likeColor: like instanceof HTMLElement ? getComputedStyle(like).color : null,
    likeFilled: heart?.getAttribute("fill"),
    repostColor: repost instanceof HTMLElement ? getComputedStyle(repost).color : null,
    bookmarkFilled: bookmark?.querySelector("svg")?.getAttribute("fill"),
    communityNoteText: document.querySelector("[data-testid=community-note-card]")?.textContent,
    videoLoop: video instanceof HTMLVideoElement ? video.loop : null,
    videoVolume: video instanceof HTMLVideoElement ? video.volume : null,
    videoMuted: video instanceof HTMLVideoElement ? video.muted : null,
    videoAutoplay: video instanceof HTMLVideoElement ? video.autoplay : null,
    videoDraggable: video instanceof HTMLVideoElement ? video.draggable : null,
    videoDragAllowed,
    videoDragPayload: videoDragTransfer.getData("text/plain"),
    videoInPlaybackZone: player?.getAttribute("data-viewport-active"),
    videoNativeControls: video instanceof HTMLVideoElement ? video.controls : null,
    videoCustomButtons: player?.querySelectorAll(".configured-video-controls button").length,
    videoCustomSliders: player?.querySelectorAll('.configured-video-controls input[type="range"]').length,
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
  engagementMetrics.bookmarkFilled !== "currentColor" ||
  engagementMetrics.videoLoop !== true ||
  engagementMetrics.videoVolume !== 1 ||
  engagementMetrics.videoMuted !== true ||
  engagementMetrics.videoAutoplay !== true ||
  engagementMetrics.videoDraggable !== false ||
  engagementMetrics.videoDragAllowed !== false ||
  engagementMetrics.videoDragPayload !== "" ||
  engagementMetrics.videoInPlaybackZone !== "true" ||
  engagementMetrics.videoNativeControls !== false ||
  Number(engagementMetrics.videoCustomButtons) < 4 ||
  engagementMetrics.videoCustomSliders !== 2 ||
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

const newPostRequestBaseline = await client.evaluate<number>("window.__qaTimelineRequests");
const readingPositionBeforeUpdate = await client.evaluate<Record<string, unknown>>(`(async () => {
  const timeline = document.querySelector("[data-testid=timeline-scroll]");
  const anchor = document.querySelector('[data-post-id="fresh-1"]');
  if (!(timeline instanceof HTMLElement) || !(anchor instanceof HTMLElement)) return { found: false };
  anchor.scrollIntoView({ block: "center" });
  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  return {
    found: true,
    postId: anchor.dataset.postId,
    postTop: anchor.getBoundingClientRect().top,
    scrollTop: timeline.scrollTop,
    scrollHeight: timeline.scrollHeight,
    clientHeight: timeline.clientHeight,
    remainingScroll: timeline.scrollHeight - timeline.scrollTop - timeline.clientHeight
  };
})()`);
if (readingPositionBeforeUpdate.found !== true) {
  throw new Error("新着追加前の読書位置を設定できませんでした。");
}
await client.evaluate(`(() => {
  window.__qaTimelineVersion = 1;
  document.dispatchEvent(new Event("visibilitychange"));
})()`);
await waitForCondition(`
  window.__qaTimelineRequests === ${newPostRequestBaseline + 1} &&
  document.querySelector(".new-post-notification") !== null &&
  document.querySelectorAll('[data-post-id^="new-"]').length === 6
`);
const newPostMetrics = await client.evaluate<Record<string, unknown>>(`(() => {
  const timeline = document.querySelector("[data-testid=timeline-scroll]");
  const anchor = document.querySelector('[data-post-id="fresh-1"]');
  const notification = document.querySelector(".new-post-notification");
  return {
    anchorTopBefore: ${Number(readingPositionBeforeUpdate.postTop)},
    anchorTopAfter: anchor?.getBoundingClientRect().top,
    scrollTopBefore: ${Number(readingPositionBeforeUpdate.scrollTop)},
    scrollTopAfter: timeline instanceof HTMLElement ? timeline.scrollTop : null,
    remainingScrollBefore: ${Number(readingPositionBeforeUpdate.remainingScroll)},
    remainingScrollAfter: timeline instanceof HTMLElement
      ? timeline.scrollHeight - timeline.scrollTop - timeline.clientHeight
      : null,
    overflowAnchor: timeline instanceof HTMLElement ? getComputedStyle(timeline).overflowAnchor : null,
    notificationText: notification?.textContent,
    notificationLabel: notification?.getAttribute("aria-label"),
    authorAvatarCount: notification?.querySelectorAll(".new-post-avatar").length,
    documentOverflow: document.documentElement.scrollWidth > innerWidth
  };
})()`);
if (
  Math.abs(Number(newPostMetrics.anchorTopAfter) - Number(newPostMetrics.anchorTopBefore)) > 1 ||
  Number(newPostMetrics.scrollTopAfter) <= Number(newPostMetrics.scrollTopBefore) ||
  newPostMetrics.overflowAnchor !== "auto" ||
  newPostMetrics.authorAvatarCount !== 5 ||
  !String(newPostMetrics.notificationText).includes("新規投稿:") ||
  newPostMetrics.notificationLabel !== "6件の新規投稿を表示" ||
  newPostMetrics.documentOverflow !== false
) {
  throw new Error(`新着追加時の位置固定検証に失敗しました: ${JSON.stringify(newPostMetrics)}`);
}
const newPostScreenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
  format: "png",
  fromSurface: true,
});
const newPostScreenshotPath = resolve(
  import.meta.dir,
  "../../target/ui-new-post-notification-768x1024.png",
);
await Bun.write(newPostScreenshotPath, Buffer.from(newPostScreenshot.data, "base64"));
const newPostNotificationClicked = await client.evaluate<boolean>(`(() => {
  const notification = document.querySelector(".new-post-notification");
  if (!(notification instanceof HTMLButtonElement)) return false;
  notification.click();
  return true;
})()`);
if (!newPostNotificationClicked) throw new Error("新規投稿通知を操作できませんでした。");
await waitForCondition(`
  document.querySelector(".new-post-notification") === null &&
  document.querySelector("[data-testid=timeline-scroll]")?.scrollTop === 0
`);
results.push({
  view: "new-post-notification",
  ...newPostMetrics,
  screenshotPath: newPostScreenshotPath,
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

const hashBeforePostDetail = await client.evaluate<string>("location.hash");
const postDetailOpened = await client.evaluate<boolean>(`(() => {
  const post = document.querySelector('[data-post-id="100"]');
  if (!(post instanceof HTMLElement)) return false;
  post.click();
  return true;
})()`);
if (!postDetailOpened) throw new Error("画像付きポスト詳細を開けませんでした。");
await waitForCondition('document.querySelector(".post-detail-content .post-image-open") !== null');
await waitForCondition(`
  document.querySelector('[data-testid="reply-sort"]')?.value === "relevance" &&
  document.querySelector(".post-detail-content")?.textContent?.includes("relevance regular reply") === true &&
  document.querySelector(".post-detail-content")?.textContent?.includes("low quality reply") === false &&
  document.querySelector(".possible-spam-toggle")?.getAttribute("aria-expanded") === "false"
`);
const likesReplySortSelected = await client.evaluate<boolean>(`(() => {
  const select = document.querySelector('[data-testid="reply-sort"]');
  if (!(select instanceof HTMLSelectElement)) return false;
  const setter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value")?.set;
  setter?.call(select, "likes");
  select.dispatchEvent(new Event("change", { bubbles: true }));
  return true;
})()`);
if (!likesReplySortSelected) throw new Error("返信をいいね順へ変更できませんでした。");
await waitForCondition(`
  window.__qaReplySortRequests.at(-1) === "likes" &&
  document.querySelector(".post-detail-content")?.textContent?.includes("likes regular reply") === true &&
  document.querySelector(".post-detail-content")?.textContent?.includes("low quality reply") === false &&
  document.querySelector(".possible-spam-toggle")?.getAttribute("aria-expanded") === "false"
`);
await waitForCondition(
  '(async () => (await (await fetch("/api/v1/settings/layout")).json()).layout.replySort === "likes")()',
);
const possibleSpamExpanded = await client.evaluate<boolean>(`(() => {
  const button = document.querySelector(".possible-spam-toggle");
  if (!(button instanceof HTMLButtonElement)) return false;
  button.click();
  return true;
})()`);
if (!possibleSpamExpanded) throw new Error("スパムの可能性のある返信を展開できませんでした。");
await waitForCondition(`
  document.querySelector(".possible-spam-toggle")?.getAttribute("aria-expanded") === "true" &&
  document.querySelector(".post-detail-content")?.textContent?.includes("low quality reply") === true &&
  document.querySelector(".post-detail-content")?.textContent?.includes("abusive quality reply") === true &&
  document.querySelector('[data-reply-thread-id="reply-regular"]')?.getAttribute("data-thread-depth") === "0" &&
  document.querySelector('[data-reply-thread-id="reply-low"]')?.getAttribute("data-thread-depth") === "1" &&
  document.querySelector('[data-reply-thread-id="reply-abusive"]')?.getAttribute("data-thread-depth") === "2"
`);
const replyControlsPositioned = await client.evaluate<boolean>(`(() => {
  const header = document.querySelector(".detail-replies-header");
  if (!(header instanceof HTMLElement)) return false;
  header.scrollIntoView({ block: "start" });
  return true;
})()`);
if (!replyControlsPositioned) throw new Error("返信並び替えUIを表示範囲へ移動できませんでした。");
const replySortingMetrics = await client.evaluate<Record<string, unknown>>(`({
  replySort: document.querySelector('[data-testid="reply-sort"]')?.value,
  requestModes: window.__qaReplySortRequests,
  possibleSpamExpanded: document.querySelector(".possible-spam-toggle")?.getAttribute("aria-expanded"),
  possibleSpamReplyCount: document.querySelectorAll(".possible-spam-replies .post-card").length,
  replyThreadDepths: Array.from(
    document.querySelectorAll("[data-reply-thread-id]"),
    element => [element.getAttribute("data-reply-thread-id"), element.getAttribute("data-thread-depth")]
  ),
  documentOverflow: document.documentElement.scrollWidth > innerWidth
})`);
if (
  replySortingMetrics.replySort !== "likes" ||
  replySortingMetrics.possibleSpamExpanded !== "true" ||
  replySortingMetrics.possibleSpamReplyCount !== 2 ||
  JSON.stringify(replySortingMetrics.replyThreadDepths) !==
    JSON.stringify([
      ["reply-regular", "0"],
      ["reply-low", "1"],
      ["reply-abusive", "2"],
    ]) ||
  replySortingMetrics.documentOverflow !== false
) {
  throw new Error(
    `返信並び替え・スパム折り畳み検証に失敗しました: ${JSON.stringify(replySortingMetrics)}`,
  );
}
const replySortingScreenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
  format: "png",
  fromSurface: true,
});
const replySortingScreenshotPath = resolve(
  import.meta.dir,
  "../../target/ui-reply-sorting-spam-768x1024.png",
);
await Bun.write(replySortingScreenshotPath, Buffer.from(replySortingScreenshot.data, "base64"));
results.push({
  view: "reply-sorting-spam",
  ...replySortingMetrics,
  screenshotPath: replySortingScreenshotPath,
});
const detailHash = await client.evaluate<string>("location.hash");
if (!detailHash.startsWith("#/post/")) {
  throw new Error(`ポスト詳細のhash routeが不正です: ${detailHash}`);
}
const imageOpened = await client.evaluate<boolean>(`(() => {
  const image = document.querySelector(".post-detail-content .post-image-open");
  if (!(image instanceof HTMLButtonElement)) return false;
  image.click();
  return true;
})()`);
if (!imageOpened) throw new Error("フルサイズ画像を開けませんでした。");
await waitForCondition('document.querySelector(".image-viewer-viewport") !== null');
const imageZoomedOut = await client.evaluate<boolean>(`(() => {
  const viewport = document.querySelector(".image-viewer-viewport");
  if (!(viewport instanceof HTMLElement)) return false;
  viewport.dispatchEvent(new WheelEvent("wheel", { deltaY: 400, clientX: 200, clientY: 300, bubbles: true }));
  return true;
})()`);
if (!imageZoomedOut) throw new Error("画像の縮小操作を実行できませんでした。");
await waitForCondition(
  'Number(document.querySelector(".image-viewer-viewport")?.getAttribute("data-zoom")) < 1',
);
const zoomBelow100 = await client.evaluate<number>(
  'Number(document.querySelector(".image-viewer-viewport")?.getAttribute("data-zoom"))',
);
await client.evaluate(`(() => {
  const viewport = document.querySelector(".image-viewer-viewport");
  viewport?.dispatchEvent(new MouseEvent("dblclick", { bubbles: true }));
})()`);
await waitForCondition(
  'Number(document.querySelector(".image-viewer-viewport")?.getAttribute("data-zoom")) === 1',
);
const releasedPointerIgnored = await client.evaluate<boolean>(`(() => {
  const viewport = document.querySelector(".image-viewer-viewport");
  if (!(viewport instanceof HTMLElement)) return false;
  viewport.dispatchEvent(new PointerEvent("pointerdown", { pointerId: 1, button: 0, buttons: 1, clientX: 100, clientY: 100, bubbles: true }));
  viewport.dispatchEvent(new PointerEvent("pointermove", { pointerId: 1, buttons: 0, clientX: 150, clientY: 135, bubbles: true }));
  return true;
})()`);
if (!releasedPointerIgnored) throw new Error("画像のポインター状態を検証できませんでした。");
await client.evaluate(
  "new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))",
);
const releasedPointerTransform = await client.evaluate<string>(
  'document.querySelector(".image-viewer-viewport img")?.style.transform ?? ""',
);
if (!releasedPointerTransform.includes("translate(0px, 0px)")) {
  throw new Error(`押されていないポインターで画像が移動しました: ${releasedPointerTransform}`);
}
const edgeBounds = await client.evaluate<{
  left: number;
  right: number;
  top: number;
  bottom: number;
} | null>(`(() => {
  const viewport = document.querySelector(".image-viewer-viewport");
  if (!(viewport instanceof HTMLElement)) return null;
  const bounds = viewport.getBoundingClientRect();
  return { left: bounds.left, right: bounds.right, top: bounds.top, bottom: bounds.bottom };
})()`);
if (edgeBounds === null) throw new Error("画像の境界実測範囲を取得できませんでした。");
const edgeCenterX = (edgeBounds.left + edgeBounds.right) / 2;
const edgeCenterY = (edgeBounds.top + edgeBounds.bottom) / 2;
await client.call("Input.dispatchMouseEvent", {
  type: "mousePressed",
  x: edgeCenterX,
  y: edgeCenterY,
  button: "left",
  buttons: 1,
  clickCount: 1,
});
await client.call("Input.dispatchMouseEvent", {
  type: "mouseMoved",
  x: edgeBounds.left,
  y: edgeCenterY,
  button: "left",
  buttons: 1,
});
await client.call("Input.dispatchMouseEvent", {
  type: "mouseReleased",
  x: edgeBounds.left,
  y: edgeCenterY,
  button: "left",
  buttons: 0,
  clickCount: 1,
});
await waitForCondition(
  'document.querySelector(".image-viewer-viewport")?.getAttribute("data-image-index") === "1" && document.querySelector(".image-viewer-viewport")?.getAttribute("data-dragging") === "false"',
);
const edgeNextIndex = await client.evaluate<string>(
  'document.querySelector(".image-viewer-viewport")?.getAttribute("data-image-index") ?? ""',
);
await client.call("Input.dispatchMouseEvent", {
  type: "mouseMoved",
  x: edgeCenterX,
  y: edgeCenterY,
  button: "none",
  buttons: 0,
});
await client.call("Input.dispatchMouseEvent", {
  type: "mousePressed",
  x: edgeCenterX,
  y: edgeCenterY,
  button: "left",
  buttons: 1,
  clickCount: 1,
});
await waitForCondition(
  'document.querySelector(".image-viewer-viewport")?.getAttribute("data-dragging") === "true"',
);
await client.call("Input.dispatchMouseEvent", {
  type: "mouseMoved",
  x: edgeCenterX + (edgeBounds.right - edgeCenterX) / 2,
  y: edgeCenterY,
  button: "left",
  buttons: 1,
});
await client.call("Input.dispatchMouseEvent", {
  type: "mouseMoved",
  x: edgeBounds.right - 0.5,
  y: edgeCenterY,
  button: "left",
  buttons: 1,
});
await client.call("Input.dispatchMouseEvent", {
  type: "mouseReleased",
  x: edgeBounds.right - 0.5,
  y: edgeCenterY,
  button: "left",
  buttons: 0,
  clickCount: 1,
});
await client.evaluate(
  "new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))",
);
const rightEdgeSnapshot = await client.evaluate<Record<string, unknown>>(`(() => {
  const viewport = document.querySelector(".image-viewer-viewport");
  if (!(viewport instanceof HTMLElement)) return { found: false };
  const bounds = viewport.getBoundingClientRect();
  return {
    found: true,
    imageIndex: viewport.getAttribute("data-image-index"),
    imageCount: viewport.getAttribute("data-image-count"),
    dragging: viewport.getAttribute("data-dragging"),
    panX: viewport.getAttribute("data-pan-x"),
    bounds: { left: bounds.left, right: bounds.right }
  };
})()`);
if (rightEdgeSnapshot.imageIndex !== "0") {
  throw new Error(`画像の右境界切替に失敗しました: ${JSON.stringify(rightEdgeSnapshot)}`);
}
const edgePreviousIndex = await client.evaluate<string>(
  'document.querySelector(".image-viewer-viewport")?.getAttribute("data-image-index") ?? ""',
);
const imageMoved = await client.evaluate<boolean>(`(() => {
  const viewport = document.querySelector(".image-viewer-viewport");
  if (!(viewport instanceof HTMLElement)) return false;
  viewport.dispatchEvent(new PointerEvent("pointerdown", { pointerId: 1, button: 0, buttons: 1, clientX: 100, clientY: 100, bubbles: true }));
  viewport.dispatchEvent(new PointerEvent("pointermove", { pointerId: 1, buttons: 1, clientX: 150, clientY: 135, bubbles: true }));
  viewport.dispatchEvent(new PointerEvent("pointerup", { pointerId: 1, buttons: 0, clientX: 150, clientY: 135, bubbles: true }));
  viewport.dispatchEvent(new WheelEvent("wheel", { deltaY: -240, clientX: 200, clientY: 300, bubbles: true }));
  return true;
})()`);
if (!imageMoved) throw new Error("画像の拡大・移動操作を実行できませんでした。");
await waitForCondition(
  'Number(document.querySelector(".image-viewer-viewport")?.getAttribute("data-zoom")) > 1',
);
const imageViewerMetrics = await client.evaluate<Record<string, unknown>>(`({
  zoomBelow100: ${zoomBelow100},
  releasedPointerTransform: ${JSON.stringify(releasedPointerTransform)},
  imageCount: document.querySelector(".image-viewer-viewport")?.getAttribute("data-image-count"),
  edgeNextIndex: ${JSON.stringify(edgeNextIndex)},
  edgePreviousIndex: ${JSON.stringify(edgePreviousIndex)},
  zoom: document.querySelector(".image-viewer-viewport")?.getAttribute("data-zoom"),
  transform: document.querySelector(".image-viewer-viewport img")?.style.transform,
  detailBehindViewer: document.querySelector(".post-detail-content") !== null,
  detailHash: ${JSON.stringify(detailHash)},
  mediaHash: location.hash,
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
  'document.querySelector(".image-viewer") === null && document.querySelector(".post-detail-content") !== null && location.hash.startsWith("#/post/")',
);
await client.evaluate(
  'document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }))',
);
await waitForCondition(
  `document.querySelector(".post-detail-content") === null && location.hash === ${JSON.stringify(hashBeforePostDetail)}`,
);
results.push({ view: "image-viewer", ...imageViewerMetrics, screenshotPath: imageScreenshotPath });

await updateSharedLayout(
  client,
  `layout => ({
    ...layout,
    display: { ...layout.display, videoLoop: false, videoVolume: 35 }
  })`,
);
await reload();
await waitForCondition('document.querySelector(".configured-video") !== null');
const persistedVideoPositioned = await client.evaluate<boolean>(`(() => {
  const player = document.querySelector(".configured-video");
  if (!(player instanceof HTMLElement)) return false;
  player.scrollIntoView({ block: "start" });
  return true;
})()`);
if (!persistedVideoPositioned)
  throw new Error("設定永続化対象の動画を再生帯へ移動できませんでした。");
await waitForCondition('document.querySelector(".post-media video") !== null');
await waitForCondition('document.querySelector(".post-media video")?.volume === 0.35');
const persistedVideoMetrics = await client.evaluate<Record<string, unknown>>(`(() => {
  const video = document.querySelector(".post-media video");
  return {
    videoLoop: video instanceof HTMLVideoElement ? video.loop : null,
    videoVolume: video instanceof HTMLVideoElement ? video.volume : null
  };
})()`);
const storedVideoLayout = await readSharedLayout<{
  display?: Record<string, unknown>;
  replySort?: unknown;
}>(client);
persistedVideoMetrics.storedLoop = storedVideoLayout.display?.videoLoop;
persistedVideoMetrics.storedVolume = storedVideoLayout.display?.videoVolume;
persistedVideoMetrics.storedReplySort = storedVideoLayout.replySort;
if (
  persistedVideoMetrics.videoLoop !== false ||
  persistedVideoMetrics.videoVolume !== 0.35 ||
  persistedVideoMetrics.storedLoop !== false ||
  persistedVideoMetrics.storedVolume !== 35 ||
  persistedVideoMetrics.storedReplySort !== "likes"
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
await waitForCondition('document.querySelector("[data-notification-kind=follow]") !== null');
const followNotificationClicked = await client.evaluate<boolean>(`(() => {
  const notification = document.querySelector("[data-notification-kind=follow]");
  if (!(notification instanceof HTMLButtonElement)) return false;
  notification.click();
  return true;
})()`);
if (!followNotificationClicked) throw new Error("フォロー通知を選択できませんでした。");
await waitForCondition(
  'document.querySelectorAll("[data-testid=follow-notification-users] .notification-user-item").length === 2',
);
const followNotificationMetrics = await client.evaluate<Record<string, unknown>>(`({
  title: document.querySelector(".modal-header h2")?.textContent,
  users: Array.from(document.querySelectorAll(".notification-user-item strong"), element => element.textContent),
  usernames: Array.from(document.querySelectorAll(".notification-user-item small"), element => element.textContent),
  documentOverflow: document.documentElement.scrollWidth > innerWidth
})`);
if (
  JSON.stringify(followNotificationMetrics.users) !== JSON.stringify(["Alice", "Bob"]) ||
  JSON.stringify(followNotificationMetrics.usernames) !== JSON.stringify(["@alice", "@bob"]) ||
  followNotificationMetrics.documentOverflow !== false
) {
  throw new Error(
    `フォロー通知のユーザー一覧検証に失敗しました: ${JSON.stringify(followNotificationMetrics)}`,
  );
}
const followNotificationScreenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
  format: "png",
  fromSurface: true,
});
const followNotificationScreenshotPath = resolve(
  import.meta.dir,
  "../../target/ui-follow-notification-users-768x1024.png",
);
await Bun.write(
  followNotificationScreenshotPath,
  Buffer.from(followNotificationScreenshot.data, "base64"),
);
results.push({
  view: "follow-notification-users",
  ...followNotificationMetrics,
  screenshotPath: followNotificationScreenshotPath,
});
await client.evaluate(
  'document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }))',
);
await waitForCondition(
  'document.querySelector("[data-testid=follow-notification-users]") === null',
);
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

await verifyUpdateButton(client);
results.push({
  view: "update-button",
  widths: [1440, 390],
  currentDownloads: 0,
  availableDownloads: 1,
});
console.info(JSON.stringify(results, null, 2));
if (browserErrors.length > 0) {
  throw new Error(`ブラウザエラーを検出しました:\n${browserErrors.join("\n")}`);
}
client.close();
