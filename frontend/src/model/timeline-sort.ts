import type { ColumnSort } from "./layout";
import type { ColumnKind } from "./layout";
import type { TimelinePost } from "./timeline";

export function sortTimelinePosts(
  posts: readonly TimelinePost[],
  kind: ColumnKind,
  sort: ColumnSort = "latest",
): TimelinePost[] {
  if (sort === "latest" || (sort === "top" && kind === "search")) {
    return [...posts];
  }
  return [...posts].sort((left, right) => {
    const scoreDifference = engagementScore(right) - engagementScore(left);
    if (scoreDifference !== 0) return scoreDifference;
    return compareNewestFirst(left, right);
  });
}

function engagementScore(post: TimelinePost): number {
  return (
    Math.max(0, post.likeCount) * 4 +
    Math.max(0, post.repostCount) * 3 +
    Math.max(0, post.quoteCount) * 3 +
    Math.max(0, post.replyCount) * 2 +
    Math.min(Number.MAX_SAFE_INTEGER, Math.max(0, post.viewCount))
  );
}

function compareNewestFirst(left: TimelinePost, right: TimelinePost): number {
  const leftTime = parseTime(left.createdAt);
  const rightTime = parseTime(right.createdAt);
  if (leftTime !== null && rightTime !== null && leftTime !== rightTime) {
    return rightTime - leftTime;
  }
  if (leftTime !== null) return -1;
  if (rightTime !== null) return 1;
  return right.id.localeCompare(left.id);
}

function parseTime(value: string | null): number | null {
  if (value === null) return null;
  const time = Date.parse(value);
  return Number.isFinite(time) ? time : null;
}
