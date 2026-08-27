import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
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
});
