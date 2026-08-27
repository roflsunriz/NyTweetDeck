import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import type { ColumnConfig } from "../model/layout";
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

  test("reports a locked vault instead of a generic timeline failure", async () => {
    let lockedEvents = 0;
    const handleLocked = () => {
      lockedEvents += 1;
    };
    window.addEventListener("nytweetdeck:vault-locked", handleLocked);
    globalThis.fetch = (async () =>
      Response.json(
        { detail: "アカウントVaultはロックされています。" },
        { status: 423 },
      )) as unknown as typeof fetch;
    const column: ColumnConfig = {
      id: "search",
      kind: "search",
      target: "NyTweetDeck",
      label: "NyTweetDeck",
    };

    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);

    expect(await screen.findByText("ログインが必要です")).toBeDefined();
    expect(screen.getByText(/Vaultを解除/)).toBeDefined();
    expect(screen.queryByText("タイムラインを読み込めませんでした。")).toBeNull();
    expect(lockedEvents).toBe(1);
    window.removeEventListener("nytweetdeck:vault-locked", handleLocked);
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
    triggerIntersection?.();

    await screen.findByText("automatic");
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

    eventSource?.emit("timeline-update", { reason: "live:error" });
    expect(await screen.findByText(/リアルタイム更新へ接続できません/)).toBeDefined();
    eventSource?.emit("timeline-update", { reason: "like", postId: "1" });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "いいね" }).classList.contains("active")).toBe(
        true,
      ),
    );
    expect(timelineLoads).toBe(1);

    eventSource?.emit("timeline-update", {
      reason: "live:tweet_engagement",
      postId: "1",
      likeCount: 9,
      repostCount: 7,
    });

    await waitFor(() =>
      expect(screen.getByRole("button", { name: "いいね" }).textContent).toBe("9"),
    );
    await waitFor(() => expect(screen.queryByText(/リアルタイム更新へ接続できません/)).toBeNull());
    expect(timelineLoads).toBe(1);

    eventSource?.emit("timeline-update", { reason: "unlike", postId: "1" });
    eventSource?.emit("timeline-update", { reason: "live:dm_update" });
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "いいね" }).classList.contains("active")).toBe(
        false,
      ),
    );
    expect(timelineLoads).toBe(1);

    eventSource?.emit("timeline-update", { reason: "create", postId: "2" });
    await waitFor(() => expect(timelineLoads).toBe(2));
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

    eventSource?.emit("timeline-update", { reason: "bookmark", postId: "1" });

    await waitFor(() => expect(timelineLoads).toBe(2));
  });

  test("filters loaded posts by text, image, and video without another request", async () => {
    let timelineLoads = 0;
    globalThis.fetch = (async (input) => {
      if (String(input).includes("/api/v1/timelines/")) {
        timelineLoads += 1;
        return Response.json({
          posts: [
            post("1", "text only"),
            { ...post("2", "image post"), media: [media("photo")] },
            { ...post("3", "video post"), media: [media("video")] },
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
    await user.click(screen.getByRole("button", { name: "画像" }));
    expect(screen.getByText("image post")).toBeDefined();
    expect(screen.queryByText("text only")).toBeNull();
    await user.click(screen.getByRole("button", { name: "動画" }));
    expect(screen.getByText("video post")).toBeDefined();
    expect(screen.queryByText("image post")).toBeNull();
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
