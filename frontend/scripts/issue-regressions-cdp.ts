import { type CdpClient, navigatePage, waitForPageCondition } from "./cdp-client";
import { readSharedLayout, updateSharedLayout } from "./shared-layout-cdp";
import type { TimelinePost } from "../src/model/timeline";
import { resolve } from "node:path";

/** 失敗応答の前から実DOMを監視し、操作後30秒未満の巻き戻しも検出する。 */
export async function verifyDeferredEngagementRollback(
  client: CdpClient,
  action: "like" | "repost",
  optimisticCount: string,
  restoredCount: string,
): Promise<number> {
  await client.evaluate(`(() => {
    const button = document.querySelector('[data-post-action=${action}]');
    const tracker = { startedAt: window.__qaEngagementClickedAt, restoredAt: null, changedEarlyAt: null, observer: null };
    const check = () => {
      const elapsed = Date.now() - tracker.startedAt;
      const optimistic = button.textContent === ${JSON.stringify(optimisticCount)} && !button.classList.contains("${action}-active") && button.getAttribute("aria-busy") === "true";
      const restored = button.textContent === ${JSON.stringify(restoredCount)} && button.classList.contains("${action}-active");
      if (!optimistic && elapsed < 29_990 && tracker.changedEarlyAt === null) tracker.changedEarlyAt = Date.now();
      if (restored && tracker.restoredAt === null) tracker.restoredAt = Date.now();
    };
    tracker.observer = new MutationObserver(check);
    tracker.observer.observe(button, { subtree: true, attributes: true, childList: true, characterData: true });
    window.__qaRollbackTracker = tracker;
    check();
    window.__qaResolvePostAction?.(503);
  })()`);
  try {
    await Bun.sleep(1_000);
    const held = await client.evaluate<boolean>(`(() => {
      const button = document.querySelector('[data-post-action=${action}]');
      return button?.textContent === ${JSON.stringify(optimisticCount)} && !button.classList.contains("${action}-active") && button.getAttribute("aria-busy") === "true" && window.__qaRollbackTracker.changedEarlyAt === null;
    })()`);
    if (!held) throw new Error(`${action}失敗直後に操作状態が巻き戻りました。`);
    await waitForPageCondition(
      client,
      `
      document.querySelector('[data-post-action=${action}]')?.textContent === ${JSON.stringify(restoredCount)} &&
      document.querySelector('[data-post-action=${action}]')?.classList.contains("${action}-active") === true &&
      document.querySelector('[data-post-action=${action}]')?.getAttribute("aria-busy") === "false" &&
      document.querySelector(".post-action-error") !== null && window.__qaRollbackTracker.restoredAt !== null
    `,
      35_000,
    );
    const timing = await client.evaluate<{ elapsed: number; early: boolean }>(`({
      elapsed: window.__qaRollbackTracker.restoredAt - window.__qaRollbackTracker.startedAt,
      early: window.__qaRollbackTracker.changedEarlyAt !== null
    })`);
    // タイマーのミリ秒単位の丸めだけを許容する。30秒後の表示維持は免除しない。
    if (timing.early || timing.elapsed < 29_990 || timing.elapsed > 35_000)
      throw new Error(`${action}失敗時の30秒保持が成立しません: ${JSON.stringify(timing)}`);
    return timing.elapsed;
  } finally {
    await client.evaluate("window.__qaRollbackTracker?.observer.disconnect()");
  }
}

interface IssueFixtureState {
  timelineVersion: number;
  requests: string[];
  resolveActions: Array<() => void>;
  wakeRequests: number;
  wakeReleases: number;
  visible: boolean;
  videoUrl: string | null;
  posterUrl: string | null;
  historyLog: Array<{ action: string; hash: string; token: string | null; index: number | null }>;
}

/** 本物の投稿・認証情報を使わない、Issue #17〜#23用の追加fixture。 */
function installFixtureInPage(): void {
  const state: IssueFixtureState = {
    timelineVersion: 0,
    requests: [],
    resolveActions: [],
    wakeRequests: 0,
    wakeReleases: 0,
    visible: true,
    videoUrl: null,
    posterUrl: null,
    historyLog: [],
  };
  Object.assign(window, { __issueFixture: state });
  const traceHistory = (action: string) =>
    state.historyLog.push({
      action,
      hash: location.hash,
      token: history.state?.nytdOverlayToken ?? null,
      index: history.state?.nytdPostDetailIndex ?? null,
    });
  const pushState = history.pushState.bind(history);
  const replaceState = history.replaceState.bind(history);
  const back = history.back.bind(history);
  history.pushState = (...args: Parameters<History["pushState"]>) => {
    pushState(...args);
    traceHistory("push");
  };
  history.replaceState = (...args: Parameters<History["replaceState"]>) => {
    replaceState(...args);
    traceHistory("replace");
  };
  history.back = () => {
    traceHistory("back");
    back();
  };
  window.addEventListener("popstate", () => traceHistory("pop"));
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    get: () => (state.visible ? "visible" : "hidden"),
  });
  Object.defineProperty(navigator, "wakeLock", {
    configurable: true,
    value: {
      request: async () => {
        state.wakeRequests += 1;
        const events = new EventTarget();
        return Object.assign(events, {
          released: false,
          async release() {
            if (this.released) return;
            this.released = true;
            state.wakeReleases += 1;
            events.dispatchEvent(new Event("release"));
          },
        });
      },
    },
  });
  const author = {
    id: "issue-author",
    username: "issue_author",
    displayName: "Issue Author",
    avatarUrl: null,
    verified: false,
  };
  const makePost = (
    id: string,
    text: string,
    replyToPostId: string | null = null,
  ): TimelinePost => ({
    id,
    text,
    language: "ja",
    createdAt: "2026-10-10T13:14:37+09:00",
    author,
    repostedBy: null,
    replyCount: 1,
    repostCount: 2,
    quoteCount: 0,
    likeCount: 3,
    bookmarkCount: 0,
    viewCount: 4,
    liked: false,
    reposted: false,
    bookmarked: false,
    replyToPostId,
    replyToUsername: replyToPostId === null ? null : author.username,
    quotedPost: null,
    communityNote: null,
    media: [],
  });
  const root = makePost("issue-root", "Original English root");
  root.language = "en";
  root.quotedPost = {
    id: "issue-quote",
    text: "Original English quote",
    language: "en",
    createdAt: "2026-10-10T13:15:49+09:00",
    author,
    media: [],
  };
  const replies = [1, 2, 3].map((depth) =>
    makePost(
      `issue-reply-${depth}`,
      `Issue recursive reply ${depth}`,
      depth === 1 ? root.id : `issue-reply-${depth - 1}`,
    ),
  );
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
    if (url.pathname === "/api/v1/timelines/homeForYou") {
      const added =
        state.timelineVersion === 0
          ? []
          : Array.from({ length: 250 }, (_, index) =>
              makePost(
                `issue-new-${state.timelineVersion}-${index}`,
                `Bulk new ${state.timelineVersion} ${index}`,
              ),
            );
      const video = makePost("issue-video-post", "Synthetic portrait video");
      video.media = [
        {
          id: "issue-video",
          type: "video",
          url: state.videoUrl ?? "",
          previewUrl: state.posterUrl ?? "",
        },
      ];
      return Response.json({
        posts: [
          ...added,
          root,
          ...(state.videoUrl === null ? [] : [video]),
          ...Array.from({ length: 45 }, (_, index) =>
            makePost(`issue-old-${index}`, `Older issue fixture ${index}`),
          ),
        ],
        nextCursor: null,
      });
    }
    if (url.pathname.endsWith("/translation") && url.pathname.startsWith("/api/v1/posts/issue-")) {
      return Response.json({
        postId: url.pathname.split("/")[4],
        sourceLanguage: "en",
        targetLanguage: "ja",
        text: "翻訳された本文",
        provider: "X",
      });
    }
    if (url.pathname.includes("/actions/") && url.pathname.startsWith("/api/v1/posts/issue-")) {
      const action = url.pathname.split("/").at(-1) ?? "";
      state.requests.push(action);
      return new Promise<Response>((resolve) =>
        state.resolveActions.push(() => resolve(new Response(null, { status: 204 }))),
      );
    }
    if (url.pathname.startsWith("/api/v1/posts/issue-")) {
      const id = url.pathname.split("/")[4];
      const post = id === root.id ? root : replies.find((item) => item.id === id);
      if (post) {
        const child = replies.find((item) => item.replyToPostId === post.id);
        return Response.json({
          post,
          replies: child ? [child] : [],
          relatedPosts: [],
          nextCursor: null,
        });
      }
    }
    if (url.pathname === `/api/v1/users/${author.id}/timeline`)
      return Response.json({ posts: [root], nextCursor: null });
    if (url.pathname === `/api/v1/users/${author.id}`)
      return Response.json({
        ...author,
        description: "Synthetic issue profile",
        bannerUrl: null,
        createdAt: "2020-01-01T00:00:00Z",
        location: null,
        website: null,
        followingCount: 0,
        followerCount: 0,
        mutualFollowerCount: 0,
        mutualFollowers: [],
        following: false,
        followsYou: false,
      });
    return originalFetch(input, init);
  };
  window.fetch = Object.assign(fixtureFetch, { preconnect: originalFetch.preconnect });
}

async function click(client: CdpClient, selector: string): Promise<void> {
  if (selector.includes("[data-video-action=")) {
    const playerPoint = await client.evaluate<{ x: number; y: number }>(`(() => {
      const rect = document.querySelector(${JSON.stringify(selector)}).closest(".configured-video").getBoundingClientRect();
      return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    })()`);
    await client.call("Input.dispatchMouseEvent", { type: "mouseMoved", ...playerPoint });
    await waitForPageCondition(
      client,
      `document.querySelector(${JSON.stringify(selector)})?.closest(".configured-video")?.getAttribute("data-controls-visible") === "true"`,
    );
  }
  const point = await client.evaluate<{ x: number; y: number } | null>(`(async () => {
    let element = document.querySelector(${JSON.stringify(selector)});
    if (!(element instanceof HTMLElement)) return null;
    const initial = element.getBoundingClientRect();
    const initialTop = document.elementsFromPoint(initial.left + initial.width / 2, initial.top + initial.height / 2)[0];
    if (!initialTop || !element.contains(initialTop)) element.scrollIntoView({ block: "center", inline: "nearest" });
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    element = document.querySelector(${JSON.stringify(selector)});
    if (!(element instanceof HTMLElement)) return null;
    const bounds = element.getBoundingClientRect();
    const x = bounds.left + bounds.width / 2, y = bounds.top + bounds.height / 2;
    const top = document.elementsFromPoint(x, y)[0];
    if (!top || !element.contains(top)) throw new Error("Issue control is obscured: " + ${JSON.stringify(selector)});
    return { x, y };
  })()`);
  if (point === null) throw new Error(`Issue検証の操作対象がありません: ${selector}`);
  await client.call("Input.dispatchMouseEvent", {
    type: "mousePressed",
    ...point,
    button: "left",
    clickCount: 1,
  });
  await client.call("Input.dispatchMouseEvent", {
    type: "mouseReleased",
    ...point,
    button: "left",
    clickCount: 1,
  });
}

interface RouteHistory {
  hash: string;
  token: string | null;
  ancestors: string[];
  detailIndex: number | null;
}

async function readRouteHistory(client: CdpClient): Promise<RouteHistory> {
  return client.evaluate<RouteHistory>(`({
    hash: location.hash,
    token: history.state?.nytdOverlayToken ?? null,
    ancestors: history.state?.nytdOverlayAncestors ?? [],
    detailIndex: history.state?.nytdPostDetailIndex ?? null
  })`);
}

function restoredHistoryCondition(history: RouteHistory): string {
  return `location.hash === ${JSON.stringify(history.hash)} &&
    (window.history.state?.nytdOverlayToken ?? null) === ${JSON.stringify(history.token)} &&
    JSON.stringify(window.history.state?.nytdOverlayAncestors ?? []) === ${JSON.stringify(JSON.stringify(history.ancestors))} &&
    (window.history.state?.nytdPostDetailIndex ?? null) === ${JSON.stringify(history.detailIndex)}`;
}

async function verifyComposer(
  client: CdpClient,
  origin: string,
  label: string,
  route: string,
): Promise<void> {
  await waitForPageCondition(
    client,
    `location.hash === ${JSON.stringify(route)} && document.querySelector(${JSON.stringify(origin)}) !== null && typeof history.state?.nytdOverlayToken === "string"`,
  );
  const originHistory = await readRouteHistory(client);
  await click(client, `${origin} [data-post-action=reply]`);
  await waitForPageCondition(
    client,
    `document.querySelector(".composer-form textarea") !== null &&
    location.hash === "#/compose" && typeof history.state?.nytdOverlayToken === "string" &&
    history.state.nytdOverlayToken !== ${JSON.stringify(originHistory.token)} &&
    history.state.nytdOverlayAncestors?.includes(${JSON.stringify(originHistory.token)}) === true`,
  );
  await click(client, ".composer-form textarea");
  await client.call("Input.insertText", { text: "Synthetic draft, never submitted" });
  const inFront = await client.evaluate<boolean>(`(() => {
    const form = document.querySelector(".composer-form");
    const textarea = form?.querySelector("textarea");
    if (!(textarea instanceof HTMLTextAreaElement)) return false;
    const bounds = textarea.getBoundingClientRect();
    const top = document.elementsFromPoint(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2)[0];
    return form.contains(top) && textarea.value === "Synthetic draft, never submitted";
  })()`);
  if (!inFront) throw new Error(`${label}から開いた返信composerが最前面で操作できません。`);
  await click(client, ".modal-backdrop:has(.composer-form) .modal-header .icon-button");
  await waitForPageCondition(
    client,
    `document.querySelector(".composer-form") === null && ${restoredHistoryCondition(originHistory)} && document.querySelector(${JSON.stringify(origin)}) !== null`,
  );
}

async function verifyTimesAndLanguage(client: CdpClient): Promise<Record<string, unknown>> {
  await waitForPageCondition(
    client,
    'document.querySelector(".quoted-post-translation-status")?.textContent?.includes("英語") === true && document.querySelector("[data-post-id=issue-root] > .post-translation-status")?.textContent?.includes("英語") === true',
  );
  const quote = await client.evaluate<boolean>(`(() => {
    const time = document.querySelector(".quoted-post-time");
    const formatter = new Intl.DateTimeFormat("ja", { year: "numeric", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit", timeZoneName: "short", hourCycle: "h23" });
    const date = new Date("2026-10-10T13:15:49+09:00");
    const parts = formatter.formatToParts(date);
    return time?.textContent === formatter.format(date) && parts.some(part => part.type === "second" && time.textContent.includes(part.value)) && parts.some(part => part.type === "timeZoneName" && time.textContent.includes(part.value));
  })()`);
  if (!quote) throw new Error("引用ポストの絶対時刻・秒・タイムゾーンが表示されません。");
  await click(client, '[data-post-id="issue-root"] .post-text');
  await waitForPageCondition(
    client,
    '[...document.querySelectorAll(".post-detail-content")].some(element => element.dataset.detailPostId === "issue-root")',
  );
  const detail = await client.evaluate<boolean>(`(() => {
    const time = document.querySelector('.post-detail-content[data-detail-post-id="issue-root"] [data-post-id="issue-root"] header time');
    const expected = new Intl.DateTimeFormat("ja", { year: "numeric", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit", timeZoneName: "short", hourCycle: "h23" }).format(new Date("2026-10-10T13:14:37+09:00"));
    return time?.textContent === expected;
  })()`);
  if (!detail) throw new Error("詳細ポストの絶対時刻・秒・タイムゾーンが表示されません。");
  return { absoluteDetailTime: true, absoluteQuoteTime: true, translatedSourceLanguage: "en" };
}

async function verifyReplyLayers(client: CdpClient, timelineHistory: RouteHistory): Promise<void> {
  await verifyComposer(
    client,
    '.post-detail-content[data-detail-post-id="issue-root"] > [data-post-id="issue-root"]',
    "詳細",
    "#/post/issue-root",
  );
  const rootHistory = await readRouteHistory(client);
  for (const depth of [1, 2, 3]) {
    const previous = depth === 1 ? "issue-root" : `issue-reply-${depth - 1}`;
    await click(
      client,
      `.post-detail-content[data-detail-post-id="${previous}"] [data-post-id="issue-reply-${depth}"] .post-text`,
    );
    await waitForPageCondition(
      client,
      `document.querySelectorAll(".post-detail-content").length === 1 && document.querySelector('.post-detail-content[data-detail-post-id="issue-reply-${depth}"]') !== null && location.hash === "#/post/issue-reply-${depth}" && history.state?.nytdPostDetailIndex === ${depth} && history.state?.nytdOverlayToken === ${JSON.stringify(rootHistory.token)}`,
    );
    await verifyComposer(
      client,
      `.post-detail-content[data-detail-post-id="issue-reply-${depth}"] > [data-post-id="issue-reply-${depth}"]`,
      `再帰返信${depth}`,
      `#/post/issue-reply-${depth}`,
    );
  }
  for (const id of ["issue-reply-3", "issue-reply-2", "issue-reply-1", "issue-root"]) {
    await click(
      client,
      `.modal-backdrop:has(>.modal-panel>.post-detail-content[data-detail-post-id="${id}"]) .modal-header .icon-button`,
    );
    if (id === "issue-root") {
      await waitForPageCondition(
        client,
        `document.querySelector(".post-detail-content") === null && ${restoredHistoryCondition(timelineHistory)}`,
      );
    } else {
      const previousIndex = Number(id.at(-1)) - 1;
      const previousId = previousIndex === 0 ? "issue-root" : `issue-reply-${previousIndex}`;
      await waitForPageCondition(
        client,
        `document.querySelectorAll(".post-detail-content").length === 1 && document.querySelector('.post-detail-content[data-detail-post-id="${previousId}"]') !== null && location.hash === "#/post/${previousId}" && history.state?.nytdOverlayToken === ${JSON.stringify(rootHistory.token)} && history.state?.nytdPostDetailIndex === ${previousIndex}`,
      );
    }
  }
  await click(client, '[data-post-id="issue-root"] .post-author-button');
  await waitForPageCondition(
    client,
    'document.querySelector(".user-profile [data-post-id=issue-root]") !== null',
  );
  await verifyComposer(
    client,
    '.user-profile [data-post-id="issue-root"]',
    "プロフィール",
    "#/user/issue-author",
  );
  await click(
    client,
    ".modal-backdrop:has(>.modal-panel>.user-profile) .modal-header .icon-button",
  );
  await waitForPageCondition(
    client,
    `document.querySelector(".user-profile") === null && ${restoredHistoryCondition(timelineHistory)}`,
  );
}

async function verifyActions(client: CdpClient): Promise<Record<string, unknown>> {
  const card = '[data-post-id="issue-root"]';
  await click(client, `${card} [data-post-action=like]`);
  const liked = await client.evaluate<boolean>(`(() => {
    const button = document.querySelector('${card} [data-post-action=like]');
    return button?.classList.contains("like-active") === true && button.getAttribute("aria-busy") === "true" && button.querySelectorAll(".like-fireworks i").length === 12 && getComputedStyle(button.querySelector(".like-fireworks")).display !== "none" && getComputedStyle(button.querySelector(".like-fireworks i"), "::after").animationName === "like-firework" && !button.classList.contains("pending") && getComputedStyle(button.querySelector("svg")).animationName === "none";
  })()`);
  if (!liked) throw new Error("通信待ちのいいねで花火・即時色変更・スピナーなしが成立しません。");
  await client.evaluate(
    "window.__issueFixture.resolveActions.splice(0).forEach(resolve => resolve())",
  );
  await waitForPageCondition(
    client,
    `document.querySelector('${card} [data-post-action=like]')?.getAttribute("aria-busy") === "false"`,
  );
  await click(client, `${card} [data-post-action=repost]`);
  await click(client, `${card} [data-post-action=repost-confirm]`);
  const reposted = await client.evaluate<boolean>(`(() => {
    const button = document.querySelector('${card} [data-post-action=repost]');
    return button?.classList.contains("repost-active") === true && button.getAttribute("aria-busy") === "true" && getComputedStyle(button).color === "rgb(29, 155, 240)";
  })()`);
  if (!reposted) throw new Error("通信待ちリポストが即座に青へ切り替わりません。");
  await client.evaluate(
    "window.__issueFixture.resolveActions.splice(0).forEach(resolve => resolve())",
  );
  return { likeFireworks: 12, likeSpinner: false, immediateRepostColor: "blue" };
}

async function verifyBulkUpdates(client: CdpClient): Promise<void> {
  await client.evaluate(`(async () => {
    document.querySelector('[data-post-id="issue-old-10"]').scrollIntoView({ block: "center" });
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    window.__issueFixture.timelineVersion = 1;
    document.dispatchEvent(new Event("visibilitychange"));
  })()`);
  await waitForPageCondition(
    client,
    'document.querySelectorAll("[data-post-id^=issue-new-1-]").length === 250 && document.querySelector(".new-post-notification") !== null',
    45_000,
  );
  await click(client, ".new-post-notification");
  const immediate = await client.evaluate<boolean>(
    'document.querySelector("[data-testid=timeline-scroll]")?.scrollTop === 0 && document.querySelector(".new-post-notification") === null',
  );
  if (!immediate) throw new Error("大量新着を押しても即時に先頭へ移動しません。");
  await client.evaluate(`(async () => {
    document.querySelector('[data-post-id="issue-old-10"]').scrollIntoView({ block: "center" });
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    window.__issueFixture.timelineVersion = 2;
    document.dispatchEvent(new Event("visibilitychange"));
  })()`);
  await waitForPageCondition(
    client,
    'document.querySelector("[data-post-id=issue-new-2-0]") !== null && document.querySelector(".new-post-notification") !== null',
    45_000,
  );
  await click(client, ".column-scroll-top");
  await waitForPageCondition(
    client,
    'document.querySelector(".new-post-notification") === null && document.querySelector("[data-testid=timeline-scroll]")?.scrollTop === 0',
  );
}

async function assertBlackVideoBars(client: CdpClient, phase: string): Promise<void> {
  await waitForPageCondition(
    client,
    'document.querySelector("[data-media-id=issue-video]")?.getAttribute("data-controls-visible") === "false"',
    4500,
  );
  await waitForPageCondition(
    client,
    'getComputedStyle(document.querySelector("[data-media-id=issue-video] .configured-video-controls")).opacity === "0"',
  );
  await client.evaluate(`(async () => {
    const video = document.querySelector('[data-media-id="issue-video"] video');
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Portrait fixture produced no painted video frame")), 3000);
      video.requestVideoFrameCallback(() => { clearTimeout(timer); resolve(); });
    });
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  })()`);
  const videoMetrics = await client.evaluate<Record<string, unknown>>(`(() => {
    const video = document.querySelector('[data-media-id="issue-video"] video');
    const style = getComputedStyle(video);
    const videoRect = video.getBoundingClientRect(), playerRect = video.closest(".configured-video").getBoundingClientRect();
    return { videoWidth: video.videoWidth, videoHeight: video.videoHeight, objectFit: style.objectFit, transform: style.transform, readyState: video.readyState, paused: video.paused, fullscreenTag: document.fullscreenElement?.tagName, elementWidth: videoRect.width, elementHeight: videoRect.height, playerWidth: playerRect.width, playerHeight: playerRect.height };
  })()`);
  if (
    Number(videoMetrics.elementWidth) > Number(videoMetrics.playerWidth) + 1 ||
    Number(videoMetrics.elementHeight) > Number(videoMetrics.playerHeight) + 1 ||
    videoMetrics.objectFit !== "contain"
  )
    throw new Error(
      `動画の全フレームをプレイヤー内へ収められません: ${JSON.stringify(videoMetrics)}`,
    );
  const bounds = await client.evaluate<{
    left: number;
    top: number;
    width: number;
    height: number;
  }>(`(() => {
    const player = document.querySelector('[data-media-id="issue-video"]');
    const video = player.querySelector("video").getBoundingClientRect();
    const frame = player.getBoundingClientRect();
    const left = Math.max(video.left, frame.left), top = Math.max(video.top, frame.top);
    return { left, top, width: Math.min(video.right, frame.right) - left, height: Math.min(video.bottom, frame.bottom) - top };
  })()`);
  const screenshot = await client.call<{ data: string }>("Page.captureScreenshot", {
    format: "png",
    fromSurface: true,
  });
  await Bun.write(
    resolve(import.meta.dir, `../../target/ui-issue-video-${phase}.png`),
    Buffer.from(screenshot.data, "base64"),
  );
  const colors = await client.evaluate<number[][]>(`(async () => {
    const image = new Image();
    image.src = "data:image/png;base64," + ${JSON.stringify(screenshot.data)};
    await image.decode();
    const canvas = document.createElement("canvas");
    canvas.width = image.width; canvas.height = image.height;
    const context = canvas.getContext("2d");
    context.drawImage(image, 0, 0);
    const rect = ${JSON.stringify(bounds)};
    const aspect = 180 / 320;
    const frameHeight = Math.min(rect.height, rect.width / aspect);
    const frameTop = rect.top + (rect.height - frameHeight) / 2;
    const center = rect.left + rect.width / 2;
    const points = [[rect.left + 8, rect.top + rect.height / 2], [center, rect.top + rect.height / 2], [rect.left + rect.width - 8, rect.top + rect.height / 2], [center, frameTop + frameHeight * .06], [center, frameTop + frameHeight * .94]];
    return points.map(([x,y]) => Array.from(context.getImageData(Math.floor(x), Math.floor(y), 1, 1).data));
  })()`);
  const black = (color: number[] | undefined) =>
    color?.slice(0, 3).every((channel) => channel < 10);
  if (
    !black(colors[0]) ||
    !black(colors[2]) ||
    Number(colors[1]?.[1]) < 100 ||
    Number(colors[3]?.[0]) < 150 ||
    Number(colors[4]?.[2]) < 150
  )
    throw new Error(
      `縦長動画の両側余白が黒になりません: ${JSON.stringify({ phase, bounds, colors, videoMetrics })}`,
    );
}

async function verifyVideoAndWakeLock(client: CdpClient): Promise<Record<string, unknown>> {
  const videoData = await client.evaluate<string>(`(async () => {
    const canvas = document.createElement("canvas");
    canvas.width = 180; canvas.height = 320;
    const context = canvas.getContext("2d");
    const paint = () => {
      context.fillStyle = "#00b400"; context.fillRect(0, 0, 180, 320);
      context.fillStyle = "#e00000"; context.fillRect(0, 0, 180, 40);
      context.fillStyle = "#0000e0"; context.fillRect(0, 280, 180, 40);
    };
    paint();
    const stream = canvas.captureStream(20);
    const recorder = new MediaRecorder(stream, { mimeType: "video/webm" });
    const chunks = [];
    recorder.ondataavailable = event => { if (event.data.size) chunks.push(event.data); };
    const stopped = new Promise(resolve => { recorder.onstop = resolve; });
    recorder.start();
    const timer = setInterval(paint, 40);
    await new Promise(resolve => setTimeout(resolve, 600));
    recorder.stop();
    await stopped;
    clearInterval(timer);
    stream.getTracks().forEach(track => track.stop());
    const blob = new Blob(chunks, { type: "video/webm" });
    const encoded = await new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result).split(",")[1]);
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
    window.__issueFixture.videoUrl = new URL("/__issue-fixture-portrait.webm", location.href).href;
    const posterCanvas = document.createElement("canvas");
    posterCanvas.width = 320; posterCanvas.height = 180;
    const posterContext = posterCanvas.getContext("2d");
    posterContext.fillStyle = "#e00000"; posterContext.fillRect(0, 0, 320, 180);
    window.__issueFixture.posterUrl = posterCanvas.toDataURL("image/png");
    return encoded;
  })()`);
  const mediaErrors: string[] = [];
  client.on("Fetch.requestPaused", (params) => {
    const event = params as { requestId: string };
    void client
      .call("Fetch.fulfillRequest", {
        requestId: event.requestId,
        responseCode: 200,
        responseHeaders: [
          { name: "Content-Type", value: "video/webm" },
          { name: "Accept-Ranges", value: "bytes" },
        ],
        body: videoData,
      })
      .catch((error: unknown) =>
        mediaErrors.push(error instanceof Error ? error.message : String(error)),
      );
  });
  // CSPを緩めず、生成した動画だけを同一originの検証URLへ供給する。
  await client.call("Fetch.enable", {
    patterns: [{ urlPattern: "*/__issue-fixture-portrait.webm", requestStage: "Request" }],
  });
  await client.evaluate('document.dispatchEvent(new Event("visibilitychange"))');
  await waitForPageCondition(
    client,
    'document.querySelector("[data-media-id=issue-video]") !== null',
    45_000,
  );
  await client.evaluate(
    'document.querySelector("[data-media-id=issue-video]").scrollIntoView({ block: "center" })',
  );
  await waitForPageCondition(
    client,
    'document.querySelector("[data-media-id=issue-video] video")?.readyState >= 2',
  );
  await client.evaluate('document.querySelector("[data-media-id=issue-video] video").play()');
  await waitForPageCondition(
    client,
    'window.__issueFixture.wakeRequests === 1 && document.querySelector("[data-media-id=issue-video] video")?.hasAttribute("poster") === false',
  );
  await assertBlackVideoBars(client, "inline");
  await click(client, '[data-media-id="issue-video"] [data-video-action=fullscreen]');
  await waitForPageCondition(
    client,
    'document.querySelector("[data-media-id=issue-video]")?.getAttribute("data-fullscreen") === "true"',
  );
  await assertBlackVideoBars(client, "fullscreen");
  await click(client, '[data-media-id="issue-video"] [data-video-action=fullscreen]');
  await waitForPageCondition(
    client,
    'document.querySelector("[data-media-id=issue-video]")?.getAttribute("data-fullscreen") === "false"',
  );
  await client.evaluate(
    'window.__issueFixture.visible = false; document.dispatchEvent(new Event("visibilitychange"))',
  );
  await waitForPageCondition(client, "window.__issueFixture.wakeReleases === 1");
  await client.evaluate(
    'window.__issueFixture.visible = true; document.dispatchEvent(new Event("visibilitychange"))',
  );
  await waitForPageCondition(client, "window.__issueFixture.wakeRequests === 2");
  await client.evaluate('document.querySelector("[data-media-id=issue-video] video").pause()');
  await waitForPageCondition(client, "window.__issueFixture.wakeReleases === 2");
  await client.evaluate('document.querySelector("[data-media-id=issue-video] video").play()');
  await waitForPageCondition(client, "window.__issueFixture.wakeRequests === 3");
  await client.evaluate(
    'document.querySelector("[data-post-id=issue-old-40]").scrollIntoView({ block: "center" })',
  );
  await waitForPageCondition(
    client,
    'window.__issueFixture.wakeReleases === 3 && document.querySelector("[data-media-id=issue-video] video") === null',
  );
  await client.call("Fetch.disable");
  if (mediaErrors.length > 0)
    throw new Error(`合成動画fixture配信に失敗しました: ${mediaErrors.join("; ")}`);
  return {
    portraitVideoBlackBars: true,
    fullscreenBlackBars: true,
    posterCleared: true,
    wakeLockRequests: 3,
    wakeLockReleases: 3,
    wakeLockMocked: true,
  };
}

export async function verifyIssueRegressions(
  client: CdpClient,
  applicationUrl: string,
  results: Array<Record<string, unknown>>,
): Promise<void> {
  const originalLayout = await readSharedLayout<Record<string, unknown>>(client);
  const installed = await client.call<{ identifier: string }>(
    "Page.addScriptToEvaluateOnNewDocument",
    { source: `(${installFixtureInPage.toString()})();` },
  );
  try {
    await client.call("Emulation.setEmulatedMedia", {
      features: [{ name: "prefers-reduced-motion", value: "no-preference" }],
    });
    await client.call("Emulation.setDeviceMetricsOverride", {
      width: 1440,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    });
    await updateSharedLayout(
      client,
      `layout => ({ ...layout, locale: "ja", translationLocale: "ja", activeAccountId: "qa-account", columns: [{ id: "issue-home", kind: "home", target: "", label: null }], display: { ...layout.display, videoAutoplay: false, videoLoop: true, autoRefreshTimelines: true, autoTranslatePosts: true, reduceMotion: false } })`,
    );
    await navigatePage(client, applicationUrl);
    await waitForPageCondition(
      client,
      'document.querySelector("[data-post-id=issue-root]") !== null',
    );
    const timelineHistory = await readRouteHistory(client);
    const times = await verifyTimesAndLanguage(client);
    await verifyReplyLayers(client, timelineHistory);
    const actions = await verifyActions(client);
    const video = await verifyVideoAndWakeLock(client);
    await verifyBulkUpdates(client);
    results.push({
      view: "issues-17-through-23",
      ...times,
      replyComposerOrigins: ["profile", "detail", "recursive-1", "recursive-2", "recursive-3"],
      ...actions,
      ...video,
      bulkNewPosts: 250,
      immediateScroll: true,
      topClearsNotification: true,
    });
  } catch (error) {
    const diagnostic = await client.evaluate<Record<string, unknown>>(`({
      hash: location.hash, token: history.state?.nytdOverlayToken ?? null,
      detailIndex: history.state?.nytdPostDetailIndex ?? null,
      activeDetail: document.querySelector(".post-detail-content")?.getAttribute("data-detail-post-id"),
      composerPresent: document.querySelector(".composer-form") !== null,
      historyLog: window.__issueFixture?.historyLog?.slice(-30)
    })`);
    console.info("Issue regression fixture history:", JSON.stringify(diagnostic));
    throw error;
  } finally {
    await client.call("Fetch.disable");
    await client.call("Page.removeScriptToEvaluateOnNewDocument", {
      identifier: installed.identifier,
    });
    await updateSharedLayout(client, `() => (${JSON.stringify(originalLayout)})`);
    await navigatePage(client, applicationUrl);
  }
}
