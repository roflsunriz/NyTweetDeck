import { describe, expect, test } from "bun:test";
import type { TimelinePage, TimelinePost } from "./timeline";
import { TimelineMemoryCache } from "./timeline-cache";

describe("timeline memory cache", () => {
  test("keeps the cursor at the last completely cached page after the 200-post limit", () => {
    const cache = new TimelineMemoryCache();
    const key = "account-and-column";

    cache.writeFirstPage(key, page(0, 100, "cursor-100"));
    cache.appendPage(key, page(100, 100, "cursor-200"));
    cache.appendPage(key, page(200, 20, "cursor-220"));

    const cached = cache.read(key);
    expect(cached?.posts).toHaveLength(200);
    expect(cached?.posts.at(-1)?.id).toBe("post-199");
    expect(cached?.nextCursor).toBe("cursor-200");
  });
});

function page(start: number, count: number, nextCursor: string | null): TimelinePage {
  return {
    posts: Array.from({ length: count }, (_, index) => post(start + index)),
    nextCursor,
  };
}

function post(index: number): TimelinePost {
  return {
    id: `post-${index}`,
    text: `post ${index}`,
    language: "ja",
    createdAt: "2026-08-28T00:00:00Z",
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
    media: [],
  };
}
