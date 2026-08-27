import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { NotificationsColumn } from "./notifications-column";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("notifications column", () => {
  test("renders non-post follow events from the web notification response", async () => {
    let requestedUrl = "";
    globalThis.fetch = (async (input: Parameters<typeof fetch>[0]) => {
      requestedUrl = String(input);
      return Response.json({
        notifications: [
          {
            id: "follow-1",
            kind: "follow",
            text: "Alice followed you",
            postId: null,
            imageUrls: ["https://pbs.twimg.com/alice.jpg"],
          },
        ],
        posts: [],
        nextCursor: null,
      });
    }) as unknown as typeof fetch;

    render(<NotificationsColumn accountId="account-1" translation={translate("ja")} />);

    const notification = await screen.findByRole("article");
    expect(notification.textContent).toContain("Alice followed you");
    expect(notification.classList.contains("deck-feed-item")).toBe(true);
    expect(notification.getAttribute("data-notification-kind")).toBe("follow");
    expect(requestedUrl).toContain("language=ja");
  });

  test("shows the target post and complete Community Note together inside NyTweetDeck", async () => {
    const requestedUrls: string[] = [];
    globalThis.fetch = (async (input: Parameters<typeof fetch>[0]) => {
      const url = String(input);
      requestedUrls.push(url);
      if (url.includes("/api/v1/community-notes/555")) {
        return Response.json({
          noteId: "555",
          text: "Context: source",
          sources: [{ fromIndex: 9, toIndex: 15, url: "https://example.com/source" }],
          post: timelinePost(),
        });
      }
      return Response.json({
        notifications: [
          {
            id: "community-1",
            kind: "community_note",
            text: "コミュニティノートが追加されました",
            noteId: "555",
            postId: null,
            imageUrls: [],
          },
        ],
        posts: [],
        nextCursor: null,
      });
    }) as unknown as typeof fetch;
    const user = userEvent.setup();

    render(<NotificationsColumn accountId="account-1" translation={translate("ja")} />);

    await user.click(
      await screen.findByRole("button", { name: /コミュニティノートが追加されました/ }),
    );
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Target post body")).toBeDefined();
    expect(within(dialog).getByText(/Context:/)).toBeDefined();
    const source = within(dialog).getByRole("link", { name: "source" });
    expect(source.getAttribute("href")).toBe("https://example.com/source");
    expect(requestedUrls.some((url) => url.includes("accountId=account-1"))).toBe(true);
    expect(requestedUrls.some((url) => url.includes("language=ja"))).toBe(true);
  });

  test("refreshes notifications when scrolling upward beyond the top", async () => {
    let notificationLoads = 0;
    globalThis.fetch = (async () => {
      notificationLoads += 1;
      return Response.json({
        notifications: [
          {
            id: String(notificationLoads),
            kind: "follow",
            text: notificationLoads === 1 ? "Before refresh" : "After refresh",
            noteId: null,
            postId: null,
            imageUrls: [],
          },
        ],
        posts: [],
        nextCursor: null,
      });
    }) as unknown as typeof fetch;

    render(<NotificationsColumn accountId="account-1" translation={translate("ja")} />);
    await screen.findByText("Before refresh");

    fireEvent.wheel(screen.getByTestId("notification-scroll"), { deltaY: -60, deltaX: 0 });

    await screen.findByText("After refresh");
    await waitFor(() => expect(notificationLoads).toBe(2));
  });
});

function timelinePost() {
  return {
    id: "987",
    text: "Target post body",
    language: "ja",
    createdAt: "2026-08-27T00:00:00Z",
    author: {
      id: "42",
      username: "alice",
      displayName: "Alice",
      avatarUrl: null,
      verified: false,
    },
    repostedBy: null,
    replyCount: 0,
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
  };
}
