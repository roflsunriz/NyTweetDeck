import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
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

  test("opens Community Note details inside NyTweetDeck even without a related post id", async () => {
    globalThis.fetch = (async () =>
      Response.json({
        notifications: [
          {
            id: "community-1",
            kind: "community_note",
            text: "コミュニティノートが追加されました",
            detailText: "この画像は2024年に撮影されたものです。",
            postId: null,
            imageUrls: [],
          },
        ],
        posts: [],
        nextCursor: null,
      })) as unknown as typeof fetch;
    const user = userEvent.setup();

    render(<NotificationsColumn accountId="account-1" translation={translate("ja")} />);

    await user.click(
      await screen.findByRole("button", { name: /コミュニティノートが追加されました/ }),
    );
    expect(screen.getByRole("heading", { name: "コミュニティノートの詳細" })).toBeDefined();
    expect(screen.getByText("この画像は2024年に撮影されたものです。")).toBeDefined();
  });

  test("offers the related post from Community Note details when X supplies a target", async () => {
    globalThis.fetch = (async () =>
      Response.json({
        notifications: [
          {
            id: "community-2",
            kind: "community_note",
            text: "関連ポストにノートがあります",
            detailText: "ノートの全文",
            postId: "987",
            imageUrls: [],
          },
        ],
        posts: [],
        nextCursor: null,
      })) as unknown as typeof fetch;
    const user = userEvent.setup();

    render(<NotificationsColumn accountId="account-1" translation={translate("ja")} />);

    await user.click(await screen.findByRole("button", { name: /関連ポストにノートがあります/ }));
    expect(screen.getByRole("button", { name: "関連するポストを表示" })).toBeDefined();
  });
});
