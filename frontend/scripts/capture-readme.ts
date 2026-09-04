import { resolve } from "node:path";
import {
  CdpClient,
  type CdpTarget,
  navigatePage,
  reloadPage,
  waitForPageCondition,
} from "./cdp-client";
import { updateSharedLayout } from "./shared-layout-cdp";

const applicationUrl = process.env.NYTWEETDECK_URL ?? "http://127.0.0.1:18080";
const cdpPort = process.env.CHROME_CDP_PORT ?? "9222";
const outputDirectory = resolve(import.meta.dir, "../../docs/images");
const targets = (await fetch(`http://127.0.0.1:${cdpPort}/json/list`).then((response) =>
  response.json(),
)) as CdpTarget[];
const page = targets.find((target) => target.type === "page");
if (page === undefined) throw new Error("READMEキャプチャ用のChromeページが見つかりません。");

const client = await CdpClient.connect(page.webSocketDebuggerUrl);
await client.call("Page.enable");
await client.call("Runtime.enable");

const landscape = svgDataUri(
  "NyTweetDeck",
  "#0f766e",
  "#38bdf8",
  '<circle cx="810" cy="165" r="76" fill="#fef3c7" opacity=".92"/><path d="M0 510 240 270l150 145 170-210 400 365H0z" fill="#082f49" opacity=".92"/><path d="M0 600V480l215-145 150 115 135-82 460 232H0z" fill="#134e4a" opacity=".9"/>',
);
const city = svgDataUri(
  "Columns",
  "#312e81",
  "#db2777",
  '<path d="M0 520h960v80H0z" fill="#111827"/><path d="M70 520V245h145v275m55 0V165h180v355m55 0V290h120v230m55 0V105h205v415" fill="#172554" stroke="#93c5fd" stroke-width="8"/><g fill="#fef08a"><circle cx="120" cy="300" r="8"/><circle cx="340" cy="225" r="8"/><circle cx="742" cy="170" r="8"/></g>',
);
const avatar = svgDataUri(
  "Ny",
  "#0369a1",
  "#22d3ee",
  '<circle cx="480" cy="280" r="150" fill="#e0f2fe" opacity=".92"/><path d="M250 600c25-170 125-250 230-250s205 80 230 250" fill="#bae6fd"/>',
);

await client.call("Page.addScriptToEvaluateOnNewDocument", {
  source: `(() => {
    const originalFetch = window.fetch.bind(window);
    const landscape = ${JSON.stringify(landscape)};
    const city = ${JSON.stringify(city)};
    const avatar = ${JSON.stringify(avatar)};
    const author = { id: "capture-user", username: "nytdeck", displayName: "NyTweetDeck", avatarUrl: avatar, verified: true };
    const post = (id, text, media = []) => ({
      id, text, language: "ja", createdAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(), author,
      repostedBy: null, conversationSection: null, replyCount: Number(id) + 2,
      repostCount: Number(id) + 5, quoteCount: 1, likeCount: Number(id) * 12 + 24,
      bookmarkCount: Number(id) + 3, viewCount: Number(id) * 420 + 1800,
      liked: id === "2", reposted: false, bookmarked: id === "1",
      replyToPostId: null, replyToUsername: null, quotedPost: null,
      communityNote: null, preTranslated: null, article: null, media
    });
    const pages = {
      home: { posts: [
        post("1", "必要なタイムラインを、ひとつの画面に。リンクはオリジナルURLで読みやすく表示します。", [
          { id: "landscape", type: "photo", url: landscape, previewUrl: landscape }
        ]),
        post("2", "新着を知らせても、読んでいる位置は動かしません。"),
        post("3", "ライト・ダーク・システム設定と、多言語表示に対応。")
      ], nextCursor: null },
      following: { posts: [
        post("4", "フォロー中のポストを時系列で確認できます。", [
          { id: "city", type: "photo", url: city, previewUrl: city }
        ]),
        post("5", "画像、動画、記事、引用ポストをカラムの中で表示。"),
        post("6", "カラムとメニューの並びは自動保存されます。")
      ], nextCursor: null }
    };
    window.fetch = (input, init) => {
      const raw = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      const url = new URL(raw, location.href);
      if (url.pathname === "/api/v1/accounts") {
        return Promise.resolve(Response.json([{ accountId: "capture-account", userId: "capture-user", username: "nytdeck", displayName: "NyTweetDeck" }]));
      }
      if (url.pathname.startsWith("/api/v1/timelines/")) {
        const kind = url.pathname.split("/").at(-1);
        return Promise.resolve(Response.json(pages[kind] ?? pages.home));
      }
      if (url.pathname === "/api/v1/posts/1") {
        return Promise.resolve(Response.json({ post: pages.home.posts[0], replies: [], nextCursor: null }));
      }
      if (url.pathname === "/api/v1/trends") {
        return Promise.resolve(Response.json({ trends: [
          { name: "#NyTweetDeck", description: "12,480 posts", rank: "1", url: "https://x.com/search?q=NyTweetDeck", domainContext: "テクノロジー", metaDescription: "いま話題" },
          { name: "カラム型クライアント", description: "8,240 posts", rank: "2", url: "https://x.com/search?q=columns", domainContext: "日本のトレンド", metaDescription: "トレンド" },
          { name: "オープンソース", description: "5,670 posts", rank: "3", url: "https://x.com/search?q=opensource", domainContext: "ソフトウェア", metaDescription: "トレンド" }
        ], nextCursor: null }));
      }
      if (url.pathname.startsWith("/api/v1/live/subscriptions/")) {
        return Promise.resolve(Response.json({ subscriptionId: "capture", connected: true, topicCount: 2 }));
      }
      return originalFetch(input, init);
    };
  })();`,
});

await navigatePage(client, applicationUrl);
await waitForPageCondition(client, 'document.querySelector(".app-shell") !== null');
await updateSharedLayout(
  client,
  `layout => ({
    ...layout,
    locale: "ja",
    theme: "dark",
    activeAccountId: "capture-account",
    columns: [
      { id: "capture-recommended", kind: "home", target: null, label: "おすすめ" },
      { id: "capture-following", kind: "following", target: null, label: "フォロー中" },
      { id: "capture-trends", kind: "trends", target: null, label: "トレンド" }
    ]
  })`,
);
await setViewport(1440, 900);
await reloadPage(client);
await waitForPageCondition(
  client,
  'document.querySelectorAll(".deck-column").length === 3 && document.querySelectorAll("[data-post-id]").length >= 6 && document.querySelectorAll(".trend-item").length === 3',
);
await capture("nytweetdeck-cover.png");
await capture("nytweetdeck-columns.png", ".column-track");

const openedPost = await client.evaluate<boolean>(`(() => {
  const post = document.querySelector('[data-post-id="1"]');
  if (!(post instanceof HTMLElement)) return false;
  post.click();
  return true;
})()`);
if (!openedPost) throw new Error("README用のポスト詳細を開けませんでした。");
await waitForPageCondition(
  client,
  'document.querySelector(".post-detail-content .post-image-open") !== null',
);
const openedImage = await client.evaluate<boolean>(`(() => {
  const button = document.querySelector('.post-detail-content .post-image-open');
  if (!(button instanceof HTMLButtonElement)) return false;
  button.click();
  return true;
})()`);
if (!openedImage) throw new Error("README用の画像ビューアを開けませんでした。");
await waitForPageCondition(client, 'document.querySelector(".image-viewer") !== null');
await setViewport(1280, 800);
await capture("nytweetdeck-image-viewer.png");

await client.evaluate(
  'document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }))',
);
await waitForPageCondition(client, 'document.querySelector(".image-viewer") === null');
await client.evaluate(
  'document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }))',
);
await waitForPageCondition(client, 'document.querySelector(".post-detail-content") === null');
const openedSettings = await client.evaluate<boolean>(`(() => {
  const button = document.querySelector('[data-action="open-settings"]');
  if (!(button instanceof HTMLButtonElement)) return false;
  button.click();
  return true;
})()`);
if (!openedSettings) throw new Error("README用の設定画面を開けませんでした。");
await waitForPageCondition(
  client,
  'document.querySelector(".settings-dialog") !== null || document.querySelector("[data-testid=refresh-api-metadata]") !== null',
);
await setViewport(768, 1024);
await capture("nytweetdeck-settings.png");

client.close();
console.info(`README images written to ${outputDirectory}`);

async function setViewport(width: number, height: number): Promise<void> {
  await client.call("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: width <= 390,
  });
}

async function capture(name: string, selector?: string): Promise<void> {
  let clip: { x: number; y: number; width: number; height: number; scale: number } | undefined;
  if (selector !== undefined) {
    const bounds = await client.evaluate<{
      x: number;
      y: number;
      width: number;
      height: number;
    } | null>(`(() => {
      const element = document.querySelector(${JSON.stringify(selector)});
      if (!(element instanceof HTMLElement)) return null;
      const rect = element.getBoundingClientRect();
      return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
    })()`);
    if (bounds === null) throw new Error(`キャプチャ対象が見つかりません: ${selector}`);
    clip = { ...bounds, scale: 1 };
  }
  const screenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
    format: "png",
    fromSurface: true,
    ...(clip === undefined ? {} : { clip }),
  });
  await Bun.write(resolve(outputDirectory, name), Buffer.from(screenshot.data, "base64"));
}

function svgDataUri(title: string, start: string, end: string, foreground: string): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="960" height="600" viewBox="0 0 960 600"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop stop-color="${start}"/><stop offset="1" stop-color="${end}"/></linearGradient></defs><rect width="960" height="600" fill="url(#g)"/>${foreground}<text x="52" y="82" fill="white" font-family="system-ui,sans-serif" font-size="42" font-weight="700">${title}</text></svg>`;
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
}
