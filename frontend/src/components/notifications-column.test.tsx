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
  test("renders non-post follow events from the Android notification response", async () => {
    globalThis.fetch = (async () =>
      Response.json({
        notifications: [
          {
            id: "follow-1",
            text: "Alice followed you",
            url: "https://x.com/notifications",
            imageUrls: ["https://pbs.twimg.com/alice.jpg"],
          },
        ],
        posts: [],
        nextCursor: null,
      })) as unknown as typeof fetch;

    render(<NotificationsColumn accountId="account-1" translation={translate("ja")} />);

    expect(await screen.findByRole("link", { name: /Alice followed you/ })).toBeDefined();
  });
});
