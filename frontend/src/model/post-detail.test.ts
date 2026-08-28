import { afterEach, describe, expect, test } from "bun:test";
import { loadPostDetail } from "./post-detail";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe("post detail requests", () => {
  test("shares only an in-flight request for the same account and post", async () => {
    let requests = 0;
    const urls: string[] = [];
    globalThis.fetch = (async (input: Parameters<typeof fetch>[0]) => {
      requests += 1;
      urls.push(String(input));
      await Promise.resolve();
      return Response.json({ post: null, replies: [], nextCursor: null });
    }) as unknown as typeof fetch;

    const first = loadPostDetail("account-1", "249");
    const second = loadPostDetail("account-1", "249");

    expect(first).toBe(second);
    await Promise.all([first, second]);
    expect(requests).toBe(1);
    await loadPostDetail("account-1", "249");
    expect(requests).toBe(2);
    await loadPostDetail("account-1", "249", "fr");
    expect(requests).toBe(3);
    await loadPostDetail("account-1", "249", "fr", "likes");
    expect(requests).toBe(4);
    expect(urls[0]).toContain("language=ja");
    expect(urls[0]).toContain("replySort=relevance");
    expect(urls[2]).toContain("language=fr");
    expect(urls[3]).toContain("replySort=likes");
  });
});
