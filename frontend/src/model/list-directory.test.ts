import { afterEach, describe, expect, test } from "bun:test";
import { loadListDirectory } from "./list-directory";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe("list directory boundary", () => {
  test("loads a validated search result with the active account", async () => {
    let requestedUrl = "";
    globalThis.fetch = (async (input) => {
      requestedUrl = String(input);
      return Response.json({
        lists: [
          {
            id: "84",
            name: "Friends",
            description: null,
            ownerName: "Alice",
            ownerUsername: "alice",
            memberCount: 5,
            subscriberCount: 2,
            source: "search",
          },
        ],
        nextCursor: null,
      });
    }) as typeof fetch;

    const page = await loadListDirectory("account-1", "search", "friends");

    expect(page.lists[0]?.name).toBe("Friends");
    expect(requestedUrl).toContain("accountId=account-1");
    expect(requestedUrl).toContain("scope=search");
    expect(requestedUrl).toContain("query=friends");
  });

  test("rejects a malformed cached candidate response", async () => {
    globalThis.fetch = (async () =>
      Response.json({
        lists: [{ id: "84", name: "Friends" }],
        nextCursor: null,
      })) as unknown as typeof fetch;

    await expect(loadListDirectory("account-1", "mine")).rejects.toThrow(
      "Invalid list directory response",
    );
  });
});
