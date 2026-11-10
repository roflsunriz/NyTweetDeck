import { clearPostDetailSnapshots, readPostDetail, prefetchPostDetail } from "./post-detail";
import { afterEach, describe, expect, jest, test } from "bun:test";
import { loadPostDetail } from "./post-detail";

const originalFetch = globalThis.fetch;

afterEach(() => {
  jest.useRealTimers();
  clearPostDetailSnapshots();
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

test("successful detail is reusable immediately and separated by account, locale and sort", async () => {
  const detail = {
    post: { id: "prefetched", author: { username: "alice" } },
    replies: [],
    nextCursor: null,
  };
  let requests = 0;
  globalThis.fetch = (async () => {
    requests++;
    return Response.json(detail);
  }) as unknown as typeof fetch;
  await loadPostDetail("cache-account", "prefetched", "ja", "relevance");
  expect(readPostDetail("cache-account", "prefetched", "ja", "relevance")?.post.id).toBe(
    "prefetched",
  );
  expect(readPostDetail("other-account", "prefetched", "ja", "relevance")).toBeNull();
  expect(readPostDetail("cache-account", "prefetched", "en", "relevance")).toBeNull();
  expect(readPostDetail("cache-account", "prefetched", "ja", "likes")).toBeNull();
  prefetchPostDetail("cache-account", "prefetched", "ja", "relevance");
  expect(requests).toBe(1);
  await loadPostDetail("cache-account", "prefetched", "ja", "relevance");
  expect(requests).toBe(2);
});

test("rate-limit reset prevents speculative requests before the upstream window reopens", async () => {
  const originalNow = Date.now;
  let now = 1_000;
  Date.now = () => now;
  let requests = 0;
  globalThis.fetch = (async () => {
    requests++;
    return requests === 1
      ? new Response(null, {
          status: 429,
          headers: { "x-rate-limit-reset": "181", "retry-after": "180" },
        })
      : Response.json({ post: null, replies: [], nextCursor: null });
  }) as unknown as typeof fetch;
  try {
    await expect(loadPostDetail("rate-account", "limited")).rejects.toThrow("HTTP 429");
    now += 61_000;
    prefetchPostDetail("rate-account", "limited", "ja", "relevance");
    expect(requests).toBe(1);
    now = 181_001;
    prefetchPostDetail("rate-account", "limited", "ja", "relevance");
    await loadPostDetail("rate-account", "limited");
    expect(requests).toBe(2);
    await Promise.resolve();
  } finally {
    Date.now = originalNow;
  }
});

test("a timed-out request releases its in-flight slot so explicit retry can succeed", async () => {
  globalThis.fetch = (async () => {
    throw new DOMException("Request timed out", "TimeoutError");
  }) as unknown as typeof fetch;
  await expect(loadPostDetail("timeout-account", "stalled")).rejects.toThrow("Request timed out");
  globalThis.fetch = (async () =>
    Response.json({ post: null, replies: [], nextCursor: null })) as unknown as typeof fetch;
  expect((await loadPostDetail("timeout-account", "stalled")).nextCursor).toBeNull();
});
