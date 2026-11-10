import { resolve } from "node:path";
import { type CdpClient, navigatePage, waitForPageCondition } from "./cdp-client";
import { readSharedLayout, updateSharedLayout } from "./shared-layout-cdp";

interface InteractionFixture {
  copied: string[];
  mediaUrls: string[];
  detailVersion: number;
  detailRequests: number;
  holdDetail: boolean;
  pendingDetails: Array<() => void>;
  timelineVersion: number;
  holdTimeline: boolean;
  pendingTimelines: Array<() => void>;
}

function installInteractionFixture(): void {
  const state: InteractionFixture = {
    copied: [],
    mediaUrls: [],
    detailVersion: 0,
    detailRequests: 0,
    holdDetail: false,
    pendingDetails: [],
    timelineVersion: 0,
    holdTimeline: false,
    pendingTimelines: [],
  };
  Object.assign(window, { __interactionFixture: state });
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: {
      writeText: async (text: string) => {
        state.copied.push(text);
      },
    },
  });
  const author = {
    id: "interaction-author",
    username: "interaction_author",
    displayName: "Interaction Author",
    avatarUrl: null,
    verified: false,
  };
  const post = (id: string, text: string) => ({
    id,
    text,
    language: "ja",
    createdAt: "2026-11-10T08:00:00Z",
    author,
    repostedBy: null,
    replyCount: 1,
    repostCount: 0,
    quoteCount: 0,
    likeCount: 0,
    bookmarkCount: 0,
    viewCount: 0,
    liked: false,
    reposted: false,
    bookmarked: false,
    replyToPostId: null,
    replyToUsername: null,
    quotedPost: null,
    communityNote: null,
    media: [],
  });
  const root = (detail = false) => {
    const icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]')?.href ?? location.href;
    state.mediaUrls = ["photo-one", "video", "photo-two"].map(
      (kind) => `${icon}?interaction=${kind}`,
    );
    return {
      ...post(
        "interaction-root",
        detail
          ? `Interaction detail version ${state.detailVersion}`
          : `Interaction timeline version ${state.timelineVersion}`,
      ),
      media: state.mediaUrls.map((url, index) => ({
        id: `interaction-media-${index}`,
        type: index === 1 ? "video" : "photo",
        url,
        previewUrl: url,
      })),
      quotedPost: {
        ...post("interaction-quote", "Interaction quoted body"),
        author: { ...author, username: "quoted_author", displayName: "Quoted Author" },
      },
    };
  };
  const originalFetch = window.fetch.bind(window);
  const fixtureFetch = async (
    input: Parameters<typeof fetch>[0],
    init?: Parameters<typeof fetch>[1],
  ) => {
    const raw = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
    const url = new URL(raw, location.href);
    if (url.pathname === "/api/v1/accounts")
      return Response.json([
        {
          accountId: "qa-account",
          userId: author.id,
          username: author.username,
          displayName: author.displayName,
        },
      ]);
    if (
      url.pathname.startsWith("/api/v1/timelines/") &&
      url.pathname !== "/api/v1/timelines/trends"
    ) {
      const reply = () => Response.json({ posts: [root()], nextCursor: null });
      if (state.holdTimeline)
        return new Promise<Response>((resolve) =>
          state.pendingTimelines.push(() => resolve(reply())),
        );
      return reply();
    }
    if (url.pathname === "/api/v1/posts/interaction-root") {
      state.detailRequests += 1;
      const reply = () =>
        Response.json({
          post: root(true),
          replies: [
            post(
              `interaction-reply-${state.detailVersion}`,
              `Interaction reply version ${state.detailVersion}`,
            ),
          ],
          contextPosts: [],
          relatedPosts: [],
          nextCursor: null,
        });
      if (state.holdDetail)
        return new Promise<Response>((resolve) =>
          state.pendingDetails.push(() => resolve(reply())),
        );
      return reply();
    }
    return originalFetch(input, init);
  };
  window.fetch = Object.assign(fixtureFetch, { preconnect: originalFetch.preconnect });
}

export async function verifyOpenInteractionIssues(
  client: CdpClient,
  applicationUrl: string,
  click: (client: CdpClient, selector: string) => Promise<void>,
): Promise<Record<string, unknown>> {
  const originalLayout = await readSharedLayout<Record<string, unknown>>(client);
  const installed = await client.call<{ identifier: string }>(
    "Page.addScriptToEvaluateOnNewDocument",
    { source: `(${installInteractionFixture.toString()})();` },
  );
  try {
    await updateSharedLayout(
      client,
      `layout => ({ ...layout, locale: "ja", activeAccountId: "qa-account", columns: [
      { id: "interaction-first", kind: "home", target: "", label: "Interaction First", sort: "latest" },
      { id: "interaction-second", kind: "following", target: "", label: "Interaction Second", sort: "top" },
      { id: "interaction-third", kind: "search", target: "interaction", label: "Interaction Third", sort: "latest" }
    ], display: { ...layout.display, mediaPreview: false, autoTranslatePosts: false, autoRefreshTimelines: false } })`,
    );
    await navigatePage(client, applicationUrl);
    const first = '.deck-column:has([data-testid="column-sort-interaction-first"])';
    const second = '.deck-column:has([data-testid="column-sort-interaction-second"])';
    const card = `${first} [data-post-id="interaction-root"]`;
    await waitForPageCondition(
      client,
      `document.querySelectorAll('[data-post-id="interaction-root"]').length === 3`,
    );

    const copies = [
      "share-media",
      "share-details-without-quote",
      "share-details-translated-without-quote",
      "share-details",
    ];
    for (let index = 0; index < copies.length; index += 1) {
      await click(client, `${card} [data-post-action=share]`);
      await click(client, `.post-share-menu [data-post-action=${copies[index]}]`);
      await waitForPageCondition(
        client,
        `window.__interactionFixture.copied.length === ${index + 1}`,
      );
    }
    const share = await client.evaluate<{ copied: string[]; mediaUrls: string[] }>(
      `({ copied: window.__interactionFixture.copied, mediaUrls: window.__interactionFixture.mediaUrls })`,
    );
    if (share.copied[0] !== share.mediaUrls.join("\n") || share.mediaUrls.length !== 3)
      throw new Error("メディアURLコピーが画像・動画の全URLと一致しません。");
    for (const copied of share.copied.slice(1, 3)) {
      if (
        !copied.includes("Interaction timeline version 0") ||
        copied.includes("Interaction quoted body") ||
        copied.includes("Quoted Author@quoted_author")
      )
        throw new Error("引用なしの詳細コピーへ引用本文・引用ユーザーが含まれました。");
    }
    if (!share.copied[3]?.includes("Interaction quoted body"))
      throw new Error("通常の詳細コピーから引用が失われました。");
    const shareViewports = await verifyShareMenuViewports(client, applicationUrl, click);

    await client.evaluate(
      "window.__interactionFixture.holdTimeline = true; window.__interactionFixture.timelineVersion = 1",
    );
    await click(client, `${second} [data-action=remove-column]`);
    await waitForPageCondition(
      client,
      `document.querySelector('${second}') === null && document.querySelector('[data-removed-column="interaction-second"]') !== null`,
    );
    await click(client, '[data-removed-column="interaction-second"] [data-action=undo-column]');
    await waitForPageCondition(
      client,
      `document.querySelector('${second} .post-text')?.textContent === "Interaction timeline version 0" && window.__interactionFixture.pendingTimelines.length > 0`,
    );
    const restored = await client.evaluate<string[]>(
      `[...document.querySelectorAll('.deck-column .column-sort-control select')].map(element => element.dataset.testid)`,
    );
    if (
      JSON.stringify(restored) !==
      JSON.stringify([
        "column-sort-interaction-first",
        "column-sort-interaction-second",
        "column-sort-interaction-third",
      ])
    )
      throw new Error(`削除カラムの元の位置を復元できません: ${JSON.stringify(restored)}`);
    await client.evaluate(
      "window.__interactionFixture.holdTimeline = false; window.__interactionFixture.pendingTimelines.splice(0).forEach(resolve => resolve())",
    );
    await waitForPageCondition(
      client,
      `document.querySelector('${second} .post-text')?.textContent === "Interaction timeline version 1" && document.querySelector('[data-testid="column-sort-interaction-second"]')?.value === "top"`,
    );
    await waitForPageCondition(
      client,
      `(async () => {
      const state = await fetch('/api/v1/settings/layout').then(response => response.json());
      return state.layout.columns.map(column => column.id).join(',') === 'interaction-first,interaction-second,interaction-third' && state.layout.columns[1].sort === 'top';
    })()`,
    );

    // Measure the real ten-second lifetime, without changing the browser clock.
    await client.evaluate("window.__interactionUndoStartedAt = performance.now()");
    await click(client, `${second} [data-action=remove-column]`);
    await waitForPageCondition(
      client,
      'document.querySelector("[data-removed-column=interaction-second]") !== null',
    );
    await Bun.sleep(9_000);
    if (
      !(await client.evaluate<boolean>(
        'document.querySelector("[data-removed-column=interaction-second]") !== null',
      ))
    )
      throw new Error("取り消し通知が10秒より前に消えました。");
    await waitForPageCondition(
      client,
      'document.querySelector("[data-removed-column=interaction-second]") === null',
      3_000,
    );
    const undoLifetime = await client.evaluate<number>(
      "performance.now() - window.__interactionUndoStartedAt",
    );
    if (undoLifetime < 9_990 || undoLifetime > 11_000)
      throw new Error(`取り消し通知の表示期間が不正です: ${undoLifetime}ms`);

    // Intent should have filled this snapshot during the preceding menu work.
    const intentPoint = await client.evaluate<{ x: number; y: number }>(`(() => {
      const bounds = document.querySelector('${card} .post-text').getBoundingClientRect();
      return { x: bounds.left + bounds.width / 2, y: bounds.top + bounds.height / 2 };
    })()`);
    await client.call("Input.dispatchMouseEvent", { type: "mouseMoved", ...intentPoint });
    await waitForPageCondition(client, "window.__interactionFixture.detailRequests >= 1");
    await client.evaluate(
      "window.__interactionFixture.holdDetail = true; window.__interactionFixture.detailVersion = 1",
    );
    await click(client, `${card} .post-text`);
    await waitForPageCondition(
      client,
      'document.querySelector(".post-detail-content > .post-card > .post-text")?.textContent === "Interaction detail version 0" && window.__interactionFixture.pendingDetails.length > 0',
    );
    await client.evaluate(
      "window.__interactionFixture.holdDetail = false; window.__interactionFixture.pendingDetails.splice(0).forEach(resolve => resolve())",
    );
    await waitForPageCondition(
      client,
      'document.querySelector(".post-detail-content > .post-card > .post-text")?.textContent === "Interaction detail version 1" && document.querySelector("[data-post-id=interaction-reply-1]") !== null',
    );
    return {
      shareAllMixedMediaUrls: true,
      shareDetailsWithoutQuote: true,
      shareTranslatedWithoutQuote: true,
      normalShareRetainsQuote: true,
      undoRestoresPositionAndSort: true,
      undoLifetimeMilliseconds: Math.round(undoLifetime),
      restoredTimelineShownBeforeResponse: true,
      restoredTimelineRevalidated: true,
      prefetchedDetailShownBeforeResponse: true,
      prefetchedDetailRevalidated: true,
      clipboardMocked: true,
      shareViewports,
    };
  } finally {
    await client.call("Page.removeScriptToEvaluateOnNewDocument", {
      identifier: installed.identifier,
    });
    await updateSharedLayout(client, `() => (${JSON.stringify(originalLayout)})`);
    await navigatePage(client, applicationUrl);
  }
}

async function verifyShareMenuViewports(
  client: CdpClient,
  applicationUrl: string,
  click: (client: CdpClient, selector: string) => Promise<void>,
): Promise<Array<Record<string, unknown>>> {
  const layout = await readSharedLayout<Record<string, unknown>>(client);
  const results: Array<Record<string, unknown>> = [];
  const trigger =
    '.deck-column:has([data-testid="column-sort-interaction-first"]) [data-post-action=share]';
  const actions = [
    "share-url",
    "share-details",
    "share-details-translated",
    "share-media",
    "share-details-without-quote",
    "share-details-translated-without-quote",
  ];
  for (const scenario of [
    { width: 390, height: 844, locale: "ja" },
    { width: 390, height: 280, locale: "ar" },
  ]) {
    await client.call("Emulation.setDeviceMetricsOverride", {
      width: scenario.width,
      height: scenario.height,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await updateSharedLayout(client, `layout => ({ ...layout, locale: "${scenario.locale}" })`);
    await navigatePage(client, applicationUrl);
    await waitForPageCondition(
      client,
      `document.querySelector('${trigger}') !== null && document.documentElement.lang === "${scenario.locale}"`,
    );
    for (let index = 0; index < actions.length; index += 1) {
      await click(client, trigger);
      await click(client, `.post-share-menu [data-post-action=${actions[index]}]`);
      await waitForPageCondition(
        client,
        `window.__interactionFixture.copied.length === ${index + 1}`,
      );
    }
    await click(client, trigger);
    const metrics = await client.evaluate<Record<string, unknown>>(`(() => {
      const menu = document.querySelector('.post-share-menu');
      const rect = menu.getBoundingClientRect();
      return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, itemCount: menu.querySelectorAll('[role=menuitem]').length, scrollHeight: menu.scrollHeight, clientHeight: menu.clientHeight, direction: getComputedStyle(menu).direction, documentOverflow: document.documentElement.scrollWidth > innerWidth };
    })()`);
    if (
      Number(metrics.left) < 0 ||
      Number(metrics.right) > scenario.width ||
      Number(metrics.top) < 0 ||
      Number(metrics.bottom) > scenario.height ||
      metrics.itemCount !== 6 ||
      metrics.documentOverflow !== false
    )
      throw new Error(
        `共有menuがviewport内に収まりません: ${JSON.stringify({ scenario, metrics })}`,
      );
    const screenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
      format: "png",
      fromSurface: true,
    });
    const screenshotPath = resolve(
      import.meta.dir,
      `../../target/ui-share-menu-${scenario.width}x${scenario.height}-${scenario.locale}.png`,
    );
    await Bun.write(screenshotPath, Buffer.from(screenshot.data, "base64"));
    const menu = ".post-share-menu";
    await client.evaluate(
      `document.querySelector('${menu} [data-post-action=share-details-translated-without-quote]').focus()`,
    );
    const bottomScreenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
      format: "png",
      fromSurface: true,
    });
    const bottomScreenshotPath = screenshotPath.replace(".png", "-last-item.png");
    await Bun.write(bottomScreenshotPath, Buffer.from(bottomScreenshot.data, "base64"));
    results.push({
      ...scenario,
      ...metrics,
      allSixActionsClicked: true,
      screenshotPath,
      bottomScreenshotPath,
    });
  }
  await client.call("Emulation.setDeviceMetricsOverride", {
    width: 1440,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false,
  });
  await updateSharedLayout(client, `() => (${JSON.stringify(layout)})`);
  await navigatePage(client, applicationUrl);
  await waitForPageCondition(
    client,
    'document.querySelectorAll("[data-post-id=interaction-root]").length === 3',
  );
  return results;
}
