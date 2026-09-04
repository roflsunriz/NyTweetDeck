import { afterEach, describe, expect, test } from "bun:test";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import type { ColumnConfig } from "../model/layout";
import { TimelineCacheProvider } from "../model/timeline-cache";
import { notifyUserSuppressed } from "../model/user-suppression";
import { TimelineColumn } from "./timeline-column";

const originalFetch = globalThis.fetch;
const originalIntersectionObserver = globalThis.IntersectionObserver;
const originalEventSource = globalThis.EventSource;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
  globalThis.IntersectionObserver = originalIntersectionObserver;
  globalThis.EventSource = originalEventSource;
});

describe("timeline column", () => {
  test("loads first page and appends a cursor page without duplicates", async () => {
    const urls: string[] = [];
    globalThis.fetch = (async (input) => {
      const url = String(input);
      urls.push(url);
      if (url.includes("cursor=next")) {
        return Response.json({
          posts: [post("1", "duplicate"), post("2", "second")],
          nextCursor: null,
        });
      }
      return Response.json({ posts: [post("1", "first")], nextCursor: "next" });
    }) as typeof fetch;
    const user = userEvent.setup();
    const column: ColumnConfig = { id: "home", kind: "home", target: null, label: null };
    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);

    await screen.findByText("first");
    await user.click(screen.getByRole("button", { name: "さらに読み込む" }));
    await screen.findByText("second");

    expect(screen.getAllByRole("article")).toHaveLength(2);
    const timelineUrls = urls.filter((url) => url.includes("/api/v1/timelines/"));
    expect(timelineUrls[0]).toContain("/api/v1/timelines/homeForYou?accountId=account-1");
    expect(timelineUrls[0]).toContain("language=ja");
    expect(timelineUrls[1]).toContain("cursor=next");
    expect(urls.some((url) => url.includes("/api/v1/live/subscriptions/"))).toBe(true);
  });

  test("renders a cached first page immediately and replaces it only after changed revalidation", async () => {
    let timelineLoads = 0;
    const pendingResponses: Array<(response: Response) => void> = [];
    globalThis.fetch = (async (input) => {
      if (!String(input).includes("/api/v1/timelines/")) {
        return Response.json({ connected: true, topicCount: 1 });
      }
      timelineLoads += 1;
      if (timelineLoads === 1) {
        return Response.json({ posts: [post("1", "cached timeline")], nextCursor: null });
      }
      return new Promise<Response>((resolve) => pendingResponses.push(resolve));
    }) as typeof fetch;
    const column: ColumnConfig = { id: "cached-home", kind: "home", target: null, label: null };
    const view = (visible: boolean) => (
      <TimelineCacheProvider>
        {visible && (
          <TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />
        )}
      </TimelineCacheProvider>
    );
    const rendered = render(view(true));
    await screen.findByText("cached timeline");

    rendered.rerender(view(false));
    rendered.rerender(view(true));

    const cachedPost = screen.getByText("cached timeline");
    expect(screen.queryByText("読み込み中")).toBeNull();
    expect(timelineLoads).toBe(2);
    act(() =>
      pendingResponses.shift()?.(
        Response.json({ posts: [post("1", "cached timeline")], nextCursor: null }),
      ),
    );
    await waitFor(() => expect(screen.getByText("cached timeline")).toBe(cachedPost));

    rendered.rerender(view(false));
    rendered.rerender(view(true));
    expect(screen.getByText("cached timeline")).toBeDefined();
    expect(timelineLoads).toBe(3);
    act(() =>
      pendingResponses.shift()?.(
        Response.json({ posts: [post("2", "updated later")], nextCursor: null }),
      ),
    );

    expect(await screen.findByText("updated later")).toBeDefined();
    expect(screen.queryByText("cached timeline")).toBeNull();
  });

  test("retains fetched cursor pages when the cached first page is unchanged", async () => {
    let timelineLoads = 0;
    let finishRevalidation: ((response: Response) => void) | undefined;
    globalThis.fetch = (async (input) => {
      const url = String(input);
      if (!url.includes("/api/v1/timelines/")) {
        return Response.json({ connected: true, topicCount: 1 });
      }
      timelineLoads += 1;
      if (url.includes("cursor=next")) {
        return Response.json({ posts: [post("2", "cached older page")], nextCursor: null });
      }
      if (timelineLoads === 1) {
        return Response.json({ posts: [post("1", "cached first page")], nextCursor: "next" });
      }
      return new Promise<Response>((resolve) => {
        finishRevalidation = resolve;
      });
    }) as typeof fetch;
    const user = userEvent.setup();
    const column: ColumnConfig = { id: "cached-pages", kind: "home", target: null, label: null };
    const view = (visible: boolean) => (
      <TimelineCacheProvider>
        {visible && (
          <TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />
        )}
      </TimelineCacheProvider>
    );
    const rendered = render(view(true));
    await screen.findByText("cached first page");
    await user.click(screen.getByRole("button", { name: "さらに読み込む" }));
    await screen.findByText("cached older page");

    rendered.rerender(view(false));
    rendered.rerender(view(true));

    const olderPage = screen.getByText("cached older page");
    expect(screen.getByText("cached first page")).toBeDefined();
    expect(screen.queryByText("読み込み中")).toBeNull();
    expect(screen.queryByRole("button", { name: "さらに読み込む" })).toBeNull();
    expect(timelineLoads).toBe(3);
    act(() =>
      finishRevalidation?.(
        Response.json({ posts: [post("1", "cached first page")], nextCursor: "next" }),
      ),
    );

    await waitFor(() => expect(screen.getByText("cached older page")).toBe(olderPage));
    expect(screen.queryByRole("button", { name: "さらに読み込む" })).toBeNull();
  });

  test("requires an active account and preserves user target", async () => {
    const column: ColumnConfig = { id: "user", kind: "user", target: "42", label: "@alice" };
    const first = render(
      <TimelineColumn column={column} accountId={null} translation={translate("ja")} />,
    );
    expect(screen.getByText("ログインが必要です")).toBeDefined();
    first.unmount();

    let requestedUrl = "";
    globalThis.fetch = (async (input) => {
      requestedUrl = String(input);
      return Response.json({ posts: [], nextCursor: null });
    }) as typeof fetch;
    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);
    await waitFor(() => expect(screen.getByText("表示するポストがありません。")).toBeDefined());
    expect(requestedUrl).toContain("/api/v1/timelines/userPosts?");
    expect(requestedUrl).toContain("target=42");
  });

  test("shows the redacted backend reason for a real timeline API failure", async () => {
    globalThis.fetch = (async () =>
      Response.json(
        { detail: "GraphQL searchに失敗しました。HTTP 400" },
        { status: 502 },
      )) as unknown as typeof fetch;
    const column: ColumnConfig = {
      id: "search",
      kind: "search",
      target: "NyTweetDeck",
      label: "NyTweetDeck",
    };

    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);

    expect(await screen.findByText(/GraphQL searchに失敗しました。HTTP 400/)).toBeDefined();
  });

  test("leaves loading after a stalled request and retries the timeline", async () => {
    let calls = 0;
    globalThis.fetch = (async (
      input: Parameters<typeof fetch>[0],
      init?: Parameters<typeof fetch>[1],
    ) => {
      if (!String(input).includes("/api/v1/timelines/")) {
        return Response.json({});
      }
      calls += 1;
      if (calls === 1) {
        return await new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener(
            "abort",
            () => reject(init.signal?.reason ?? new DOMException("Aborted", "AbortError")),
            { once: true },
          );
        });
      }
      return Response.json({ posts: [post("2", "recovered")], nextCursor: null });
    }) as unknown as typeof fetch;
    const user = userEvent.setup();
    const column: ColumnConfig = { id: "home-timeout", kind: "home", target: null, label: null };
    render(
      <TimelineColumn
        column={column}
        accountId="account-1"
        translation={translate("ja")}
        requestTimeoutMilliseconds={5}
      />,
    );

    expect(await screen.findByText("タイムラインを読み込めませんでした。")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "再試行" }));

    expect(await screen.findByText("recovered")).toBeDefined();
    expect(calls).toBe(2);
  });

  test("loads the next page automatically when the end sentinel becomes visible", async () => {
    let triggerIntersection: (() => void) | undefined;
    globalThis.IntersectionObserver = class {
      constructor(callback: IntersectionObserverCallback) {
        triggerIntersection = () =>
          callback(
            [{ isIntersecting: true } as IntersectionObserverEntry],
            this as unknown as IntersectionObserver,
          );
      }

      observe() {}
      disconnect() {}
      unobserve() {}
      takeRecords(): IntersectionObserverEntry[] {
        return [];
      }
    } as unknown as typeof IntersectionObserver;
    globalThis.fetch = (async (input) => {
      const url = String(input);
      return url.includes("cursor=next")
        ? Response.json({ posts: [post("2", "automatic")], nextCursor: null })
        : Response.json({ posts: [post("1", "first")], nextCursor: "next" });
    }) as typeof fetch;
    const column: ColumnConfig = { id: "home", kind: "home", target: null, label: null };

    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);
    await screen.findByText("first");
    await waitFor(() => expect(triggerIntersection).toBeDefined());
    act(() => triggerIntersection?.());

    await screen.findByText("automatic");
  });

  test("treats an upward wheel gesture beyond the top as one manual refresh", async () => {
    let timelineLoads = 0;
    globalThis.fetch = (async (input) => {
      if (!String(input).includes("/api/v1/timelines/")) {
        return Response.json({ connected: true });
      }
      timelineLoads += 1;
      return Response.json({
        posts: [timelineLoads === 1 ? post("1", "before manual refresh") : post("2", "refreshed")],
        nextCursor: null,
      });
    }) as typeof fetch;
    const column: ColumnConfig = { id: "home", kind: "home", target: null, label: null };

    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);
    await screen.findByText("before manual refresh");
    const timeline = screen.getByTestId("timeline-scroll");

    timeline.scrollTop = 40;
    fireEvent.wheel(timeline, { deltaY: -80, deltaX: 0 });
    timeline.scrollTop = 0;
    fireEvent.wheel(timeline, { deltaY: -20, deltaX: 60 });
    expect(timelineLoads).toBe(1);

    fireEvent.wheel(timeline, { deltaY: -80, deltaX: 0 });
    fireEvent.wheel(timeline, { deltaY: -40, deltaX: 0 });

    await screen.findByText("refreshed");
    expect(timelineLoads).toBe(2);
  });

  test("does not manually refresh until a touch pull starts at the top and passes the threshold", async () => {
    let timelineLoads = 0;
    globalThis.fetch = (async (input) => {
      if (String(input).includes("/api/v1/timelines/")) {
        timelineLoads += 1;
        return Response.json({
          posts: [post(String(timelineLoads), "touch timeline")],
          nextCursor: null,
        });
      }
      return Response.json({ connected: true });
    }) as typeof fetch;
    const column: ColumnConfig = { id: "touch", kind: "home", target: null, label: null };

    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);
    await screen.findByText("touch timeline");
    const timeline = screen.getByTestId("timeline-scroll");

    timeline.scrollTop = 20;
    fireEvent.touchStart(timeline, { touches: [{ clientY: 100 }] });
    fireEvent.touchMove(timeline, { touches: [{ clientY: 180 }] });
    fireEvent.touchEnd(timeline);
    timeline.scrollTop = 0;

    fireEvent.touchStart(timeline, { touches: [{ clientY: 100 }] });
    fireEvent.touchMove(timeline, { touches: [{ clientY: 130 }] });
    fireEvent.touchEnd(timeline);
    expect(timelineLoads).toBe(1);

    fireEvent.touchStart(timeline, { touches: [{ clientY: 100 }] });
    fireEvent.touchMove(timeline, { touches: [{ clientY: 160 }] });
    fireEvent.touchEnd(timeline);

    await waitFor(() => expect(timelineLoads).toBe(2));
  });

  test("updates engagement in place and refreshes only for timeline membership changes", async () => {
    let eventSource: FakeEventSource | undefined;
    globalThis.EventSource = class extends FakeEventSource {
      constructor(_url: string | URL) {
        super();
        eventSource = this;
      }
    } as unknown as typeof EventSource;
    let timelineLoads = 0;
    globalThis.fetch = (async (input) => {
      if (String(input).includes("/api/v1/timelines/")) {
        timelineLoads += 1;
        return Response.json({ posts: [post("1", "live post")], nextCursor: null });
      }
      return Response.json({ connected: true, topicCount: 1 });
    }) as typeof fetch;
    const column: ColumnConfig = { id: "home", kind: "home", target: null, label: null };
    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);
    await screen.findByText("live post");

    act(() => eventSource?.emit("timeline-update", { reason: "live:error" }));
    expect(await screen.findByText(/リアルタイム更新へ接続できません/)).toBeDefined();
    act(() => eventSource?.emit("timeline-update", { reason: "like", postId: "1" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "いいね" }).classList.contains("active")).toBe(
        true,
      ),
    );
    expect(timelineLoads).toBe(1);

    act(() =>
      eventSource?.emit("timeline-update", {
        reason: "live:tweet_engagement",
        postId: "1",
        likeCount: 9,
        repostCount: 7,
      }),
    );

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "いいね" }).textContent).toBe("9"),
    );
    await waitFor(() => expect(screen.queryByText(/リアルタイム更新へ接続できません/)).toBeNull());
    expect(timelineLoads).toBe(1);

    act(() => {
      eventSource?.emit("timeline-update", { reason: "unlike", postId: "1" });
      eventSource?.emit("timeline-update", { reason: "live:dm_update" });
    });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "いいね" }).classList.contains("active")).toBe(
        false,
      ),
    );
    expect(timelineLoads).toBe(1);

    act(() => eventSource?.emit("timeline-update", { reason: "create", postId: "2" }));
    await waitFor(() => expect(timelineLoads).toBe(2));
  });

  test("removes every post authored or reposted by a muted or blocked user", async () => {
    let eventSource: FakeEventSource | undefined;
    globalThis.EventSource = class extends FakeEventSource {
      constructor(_url: string | URL) {
        super();
        eventSource = this;
      }
    } as unknown as typeof EventSource;
    const repostedByMutedUser = {
      ...postByAuthor("2", "muted repost", "99", null),
      repostedBy: post("reposter", "").author,
    };
    globalThis.fetch = (async (input) =>
      String(input).includes("/api/v1/timelines/")
        ? Response.json({
            posts: [
              post("1", "muted original"),
              repostedByMutedUser,
              postByAuthor("3", "other", "99", null),
            ],
            nextCursor: null,
          })
        : Response.json({ connected: true })) as typeof fetch;
    render(
      <TimelineColumn
        column={{ id: "home", kind: "home", target: null, label: null }}
        accountId="account-1"
        translation={translate("ja")}
      />,
    );
    await screen.findByText("muted original");

    act(() => notifyUserSuppressed({ accountId: "account-1", userId: "42" }));
    await waitFor(() => expect(screen.queryByText("muted original")).toBeNull());
    expect(screen.queryByText("muted repost")).toBeNull();
    expect(screen.getByText("other")).toBeDefined();

    act(() => eventSource?.emit("timeline-update", { reason: "block", userId: "99" }));
    await screen.findByText("表示するポストがありません。");
  });

  test("prepends new posts while preserving the visible post and shows five author avatars", async () => {
    let eventSource: FakeEventSource | undefined;
    globalThis.EventSource = class extends FakeEventSource {
      constructor(_url: string | URL) {
        super();
        eventSource = this;
      }
    } as unknown as typeof EventSource;
    let timelineLoads = 0;
    globalThis.fetch = (async (input) => {
      if (!String(input).includes("/api/v1/timelines/")) {
        return Response.json({ connected: true, topicCount: 1 });
      }
      timelineLoads += 1;
      if (timelineLoads === 1) {
        return Response.json({ posts: [post("old", "post being read")], nextCursor: null });
      }
      return Response.json({
        posts: [
          ...Array.from({ length: 6 }, (_, index) =>
            postByAuthor(
              `new-${index}`,
              `new post ${index}`,
              `author-${index}`,
              index % 2 === 0 ? `https://pbs.twimg.com/avatar-${index}.jpg` : null,
            ),
          ),
          post("old", "post being read"),
        ],
        nextCursor: null,
      });
    }) as typeof fetch;
    const user = userEvent.setup();
    const column: ColumnConfig = { id: "stable", kind: "home", target: null, label: null };

    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);
    const oldText = await screen.findByText("post being read");
    const oldCard = oldText.closest<HTMLElement>(".post-card");
    const timeline = screen.getByTestId("timeline-scroll");
    expect(oldCard).not.toBeNull();
    timeline.scrollTop = 0;
    timeline.getBoundingClientRect = () => rectangle(0, 600);
    if (oldCard !== null) {
      oldCard.getBoundingClientRect = () =>
        rectangle(screen.queryByText("new post 0") === null ? 100 : 300, 180);
    }

    act(() => eventSource?.emit("timeline-update", { reason: "create", postId: "new-0" }));

    const notification = await screen.findByRole("button", { name: "6件の新規投稿を表示" });
    await waitFor(() => expect(timeline.scrollTop).toBe(200));
    expect(notification.textContent).toContain("新規投稿:");
    expect(notification.querySelectorAll(".new-post-avatar")).toHaveLength(5);
    expect(screen.getByText("post being read")).toBeDefined();
    expect(screen.getByText("new post 0")).toBeDefined();

    await user.click(notification);

    expect(timeline.scrollTop).toBe(0);
    expect(screen.queryByRole("button", { name: "6件の新規投稿を表示" })).toBeNull();
  });

  test("keeps the central reading post when a sliver of another post remains above it", async () => {
    let eventSource: FakeEventSource | undefined;
    globalThis.EventSource = class extends FakeEventSource {
      constructor(_url: string | URL) {
        super();
        eventSource = this;
      }
    } as unknown as typeof EventSource;
    let timelineLoads = 0;
    globalThis.fetch = (async (input) => {
      if (!String(input).includes("/api/v1/timelines/")) {
        return Response.json({ connected: true, topicCount: 1 });
      }
      timelineLoads += 1;
      return Response.json({
        posts:
          timelineLoads === 1
            ? [post("sliver", "partially visible post"), post("old", "post being read upward")]
            : [
                post("new", "new post above"),
                post("sliver", "partially visible post"),
                post("old", "post being read upward"),
              ],
        nextCursor: null,
      });
    }) as typeof fetch;
    const column: ColumnConfig = { id: "native-anchor", kind: "home", target: null, label: null };

    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);
    const oldText = await screen.findByText("post being read upward");
    const sliverText = await screen.findByText("partially visible post");
    const oldCard = oldText.closest<HTMLElement>(".post-card");
    const sliverCard = sliverText.closest<HTMLElement>(".post-card");
    const timeline = screen.getByTestId("timeline-scroll");
    if (oldCard === null || sliverCard === null) {
      throw new Error("表示位置を固定する投稿がありません。");
    }
    timeline.scrollTop = 800;
    timeline.getBoundingClientRect = () => rectangle(0, 600);
    sliverCard.getBoundingClientRect = () => rectangle(-60, 70);
    oldCard.getBoundingClientRect = () =>
      screen.queryByText("new post above") === null ? rectangle(250, 80) : rectangle(450, 80);

    act(() => eventSource?.emit("timeline-update", { reason: "create", postId: "new" }));

    await screen.findByText("new post above");
    await waitFor(() => expect(timeline.scrollTop).toBe(1_000));
  });

  test("keeps the live subscription while engagement-only state changes", async () => {
    let eventSource: FakeEventSource | undefined;
    globalThis.EventSource = class extends FakeEventSource {
      constructor(_url: string | URL) {
        super();
        eventSource = this;
      }
    } as unknown as typeof EventSource;
    const subscriptionMethods: string[] = [];
    globalThis.fetch = (async (input, init) => {
      const url = String(input);
      if (url.includes("/api/v1/timelines/")) {
        return Response.json({ posts: [post("1", "stable subscription")], nextCursor: null });
      }
      if (url.includes("/api/v1/live/subscriptions/")) {
        subscriptionMethods.push(init?.method ?? "GET");
      }
      return Response.json({ connected: true, topicCount: 1 });
    }) as typeof fetch;
    const column: ColumnConfig = { id: "home", kind: "home", target: null, label: null };
    const rendered = render(
      <TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />,
    );
    await screen.findByText("stable subscription");
    await waitFor(() => expect(subscriptionMethods).toEqual(["PUT"]));

    act(() =>
      eventSource?.emit("timeline-update", {
        reason: "live:tweet_engagement",
        postId: "1",
        likeCount: 8,
      }),
    );

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "いいね" }).textContent).toBe("8"),
    );
    expect(subscriptionMethods).toEqual(["PUT"]);

    rendered.unmount();
    await waitFor(() => expect(subscriptionMethods).toEqual(["PUT", "DELETE"]));
  });

  test("refreshes visible timelines conservatively when X has no new-post push topic", async () => {
    let timelineLoads = 0;
    globalThis.fetch = (async (input) => {
      if (String(input).includes("/api/v1/timelines/")) {
        timelineLoads += 1;
        return Response.json({
          posts: [
            timelineLoads === 1
              ? post("1", "initial timeline")
              : post("2", "externally published post"),
          ],
          nextCursor: null,
        });
      }
      return Response.json({ connected: true, topicCount: 1 });
    }) as typeof fetch;
    const column: ColumnConfig = { id: "home", kind: "home", target: null, label: null };

    render(
      <TimelineColumn
        column={column}
        accountId="account-1"
        translation={translate("ja")}
        refreshMinimumMilliseconds={5}
        refreshMaximumMilliseconds={10}
        refreshGlobalGapMilliseconds={0}
      />,
    );

    await screen.findByText("initial timeline");
    await screen.findByText("externally published post");
    expect(timelineLoads).toBeGreaterThanOrEqual(2);
  });

  test("stops automatic membership refresh when the user disables it", async () => {
    let eventSource: FakeEventSource | undefined;
    globalThis.EventSource = class extends FakeEventSource {
      constructor(_url: string | URL) {
        super();
        eventSource = this;
      }
    } as unknown as typeof EventSource;
    let timelineLoads = 0;
    globalThis.fetch = (async (input) => {
      if (String(input).includes("/api/v1/timelines/")) {
        timelineLoads += 1;
        return Response.json({ posts: [post("1", "manual only")], nextCursor: null });
      }
      return Response.json({ connected: true, topicCount: 1 });
    }) as typeof fetch;
    const column: ColumnConfig = { id: "manual", kind: "home", target: null, label: null };

    render(
      <TimelineColumn
        column={column}
        accountId="account-1"
        translation={translate("ja")}
        autoRefreshTimelines={false}
        refreshMinimumMilliseconds={5}
        refreshMaximumMilliseconds={5}
        refreshGlobalGapMilliseconds={0}
      />,
    );
    await screen.findByText("manual only");

    act(() => eventSource?.emit("timeline-update", { reason: "create", postId: "2" }));
    await new Promise((resolve) => globalThis.setTimeout(resolve, 30));

    expect(timelineLoads).toBe(1);
  });

  test("refreshes bookmarks only in the history column", async () => {
    let eventSource: FakeEventSource | undefined;
    globalThis.EventSource = class extends FakeEventSource {
      constructor(_url: string | URL) {
        super();
        eventSource = this;
      }
    } as unknown as typeof EventSource;
    let timelineLoads = 0;
    globalThis.fetch = (async (input) => {
      if (String(input).includes("/api/v1/timelines/")) {
        timelineLoads += 1;
        return Response.json({ posts: [post("1", "saved")], nextCursor: null });
      }
      return Response.json({ connected: true, topicCount: 1 });
    }) as typeof fetch;
    const column: ColumnConfig = { id: "history", kind: "history", target: null, label: null };
    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);
    await screen.findByText("saved");

    act(() => eventSource?.emit("timeline-update", { reason: "bookmark", postId: "1" }));

    await waitFor(() => expect(timelineLoads).toBe(2));
  });

  test("combines post types and excludes reposts without another request", async () => {
    let timelineLoads = 0;
    globalThis.fetch = (async (input) => {
      if (String(input).includes("/api/v1/timelines/")) {
        timelineLoads += 1;
        return Response.json({
          posts: [
            post("1", "text only"),
            { ...post("2", "image post"), media: [media("photo")] },
            { ...post("3", "video post"), media: [media("video")] },
            {
              ...post("4", "reposted image"),
              repostedBy: {
                id: "84",
                username: "bob",
                displayName: "Bob",
                avatarUrl: null,
                verified: false,
              },
              media: [media("photo")],
            },
          ],
          nextCursor: null,
        });
      }
      return Response.json({ connected: true });
    }) as typeof fetch;
    const user = userEvent.setup();
    const column: ColumnConfig = { id: "home", kind: "home", target: null, label: null };
    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);

    await screen.findByText("text only");
    const filterToolbar = screen.getByRole("toolbar", { name: "ポストを複数条件で絞り込む" });
    const filterButtons = within(filterToolbar).getAllByRole("button");
    expect(filterButtons).toHaveLength(5);
    for (const button of filterButtons) {
      expect(button.textContent).toBe("");
      expect(button.querySelector("svg")).not.toBeNull();
      expect(button.getAttribute("title")).toBe(button.getAttribute("aria-label"));
    }
    await user.click(screen.getByRole("button", { name: "画像" }));
    expect(screen.getByText("image post")).toBeDefined();
    expect(screen.getByText("reposted image")).toBeDefined();
    expect(screen.queryByText("text only")).toBeNull();
    await user.click(screen.getByRole("button", { name: "動画" }));
    expect(screen.getByText("video post")).toBeDefined();
    expect(screen.getByText("image post")).toBeDefined();
    expect(screen.getByRole("button", { name: "画像" }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: "動画" }).getAttribute("aria-pressed")).toBe("true");

    await user.click(screen.getByRole("button", { name: "リポストを除く" }));
    expect(screen.queryByText("reposted image")).toBeNull();
    expect(screen.getByText("image post")).toBeDefined();
    expect(screen.getByText("video post")).toBeDefined();

    await user.click(screen.getByRole("button", { name: "画像" }));
    expect(screen.queryByText("image post")).toBeNull();
    expect(screen.getByText("video post")).toBeDefined();

    await user.click(screen.getByRole("button", { name: "すべて" }));
    expect(screen.getByText("text only")).toBeDefined();
    expect(screen.getByText("image post")).toBeDefined();
    expect(screen.getByText("video post")).toBeDefined();
    expect(screen.queryByText("reposted image")).toBeNull();

    await user.click(screen.getByRole("button", { name: "リポストを除く" }));
    expect(screen.getByText("reposted image")).toBeDefined();
    expect(timelineLoads).toBe(1);
  });
});

function media(type: "photo" | "video") {
  return { id: type, type, url: `https://pbs.twimg.com/${type}`, previewUrl: "" };
}

function post(id: string, text: string) {
  return {
    id,
    text,
    language: "ja",
    createdAt: "2026-08-26T00:00:00Z",
    author: {
      id: "42",
      username: "alice",
      displayName: "Alice",
      avatarUrl: null,
      verified: false,
    },
    repostedBy: null,
    replyCount: 1,
    repostCount: 2,
    quoteCount: 0,
    likeCount: 3,
    bookmarkCount: 4,
    viewCount: 5,
    liked: false,
    reposted: false,
    bookmarked: false,
    quotedPost: null,
    media: [],
  };
}

function postByAuthor(id: string, text: string, authorId: string, avatarUrl: string | null) {
  return {
    ...post(id, text),
    author: {
      id: authorId,
      username: authorId,
      displayName: `Author ${authorId}`,
      avatarUrl,
      verified: false,
    },
  };
}

function rectangle(top: number, height: number): DOMRect {
  return {
    x: 0,
    y: top,
    top,
    right: 320,
    bottom: top + height,
    left: 0,
    width: 320,
    height,
    toJSON: () => ({}),
  };
}

class FakeEventSource {
  private readonly listeners = new Map<string, (event: Event) => void>();

  addEventListener(type: string, listener: EventListenerOrEventListenerObject | null): void {
    if (typeof listener === "function") {
      this.listeners.set(type, listener as EventListener);
    }
  }

  removeEventListener(type: string): void {
    this.listeners.delete(type);
  }

  close(): void {}

  emit(type: string, value: object): void {
    const event = new MessageEvent(type, { data: JSON.stringify(value) });
    this.listeners.get(type)?.(event);
  }
}
