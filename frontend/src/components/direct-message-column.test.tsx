import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
import { translate } from "../i18n/translations";
import { DirectMessageColumn } from "./direct-message-column";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("direct message column", () => {
  test("loads the authenticated web inbox", async () => {
    const requestedUrls: string[] = [];
    globalThis.fetch = (async (input) => {
      requestedUrls.push(String(input));
      return Response.json({
        messages: [
          {
            id: "1",
            conversationId: "42-7",
            senderId: "42",
            senderName: "Alice",
            senderUsername: "alice",
            senderAvatarUrl: null,
            text: "hello",
            timestamp: 100,
          },
        ],
        nextCursor: null,
      });
    }) as typeof fetch;

    render(<DirectMessageColumn accountId="account-1" translation={translate("ja")} />);

    await screen.findByText("hello");
    expect(screen.getByText("@alice")).toBeDefined();
    expect(requestedUrls).toContain("/api/v1/messages?accountId=account-1");
    expect(requestedUrls).toContain("/api/v1/live/subscriptions/messages%3Adirect-messages");
  });
});
