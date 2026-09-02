import { expect, test } from "bun:test";
import type { TimelinePost } from "./timeline";
import { sortTimelinePosts } from "./timeline-sort";

test("keeps latest and search Top response order stable", () => {
  const posts = [post("new", 1), post("old", 99)];
  expect(sortTimelinePosts(posts, "home", "latest").map((item) => item.id)).toEqual(["new", "old"]);
  expect(sortTimelinePosts(posts, "search", "top")).toEqual(posts);
});

test("orders non-search Top columns by engagement and uses recency as a tie breaker", () => {
  const posts = [post("old-popular", 10), post("new-popular", 10), post("quiet", 0)];
  // biome-ignore lint/style/noNonNullAssertion: test helper mutates tuple entries
  posts[0] = { ...posts[0]!, createdAt: "2026-08-28T00:00:00Z" };
  // biome-ignore lint/style/noNonNullAssertion: test helper mutates tuple entries
  posts[1] = { ...posts[1]!, createdAt: "2026-08-29T00:00:00Z" };
  expect(sortTimelinePosts(posts, "home", "top").map((item) => item.id)).toEqual([
    "new-popular",
    "old-popular",
    "quiet",
  ]);
});

function post(id: string, likeCount: number): TimelinePost {
  return {
    id,
    text: id,
    language: "ja",
    createdAt: "2026-08-28T00:00:00Z",
    author: { id: "1", username: "user", displayName: "User", avatarUrl: null, verified: false },
    repostedBy: null,
    replyCount: 0,
    repostCount: 0,
    quoteCount: 0,
    likeCount,
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
