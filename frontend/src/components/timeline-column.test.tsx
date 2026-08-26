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
    const column: ColumnConfig = { id: "home", kind: "home", target: null };
    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);

    await screen.findByText("first");
    await user.click(screen.getByRole("button", { name: "さらに読み込む" }));
    await screen.findByText("second");

    expect(screen.getAllByRole("article")).toHaveLength(2);
    const timelineUrls = urls.filter((url) => url.includes("/api/v1/timelines/"));
    expect(timelineUrls[0]).toContain("/api/v1/timelines/homeForYou?accountId=account-1");
    expect(timelineUrls[1]).toContain("cursor=next");
    expect(urls.some((url) => url.includes("/api/v1/live/subscriptions/"))).toBe(true);
  });

  test("requires an active account and preserves user target", async () => {
    const column: ColumnConfig = { id: "user", kind: "user", target: "42" };
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
    const column: ColumnConfig = { id: "home", kind: "home", target: null };

    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);
    await screen.findByText("first");
    await waitFor(() => expect(triggerIntersection).toBeDefined());
    triggerIntersection?.();

    await screen.findByText("automatic");
  });

  test("shows a reconnect warning for pipeline errors and refreshes on engagement", async () => {
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
    const column: ColumnConfig = { id: "home", kind: "home", target: null };
    render(<TimelineColumn column={column} accountId="account-1" translation={translate("ja")} />);
    await screen.findByText("live post");

    eventSource?.emit("timeline-update", { reason: "live:error" });
    expect(await screen.findByText(/リアルタイム更新へ接続できません/)).toBeDefined();
    eventSource?.emit("timeline-update", { reason: "live:tweet_engagement" });

    await waitFor(() => expect(timelineLoads).toBe(2));
    await waitFor(() => expect(screen.queryByText(/リアルタイム更新へ接続できません/)).toBeNull());
  });
});

function post(id: string, text: string) {
  return {
    id,
    text,
    createdAt: "2026-08-26T00:00:00Z",
    author: {
      id: "42",
      username: "alice",
      displayName: "Alice",
      avatarUrl: null,
      verified: false,
    },
    replyCount: 1,
    repostCount: 2,
    quoteCount: 0,
    likeCount: 3,
    bookmarkCount: 4,
    viewCount: 5,
    liked: false,
    reposted: false,
    bookmarked: false,
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
