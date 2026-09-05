import type { TimelinePost } from "../components/post-card";
import type { Locale, ReplySort } from "./layout";

export interface PostDetail {
  post: TimelinePost;
  contextPosts?: TimelinePost[];
  replies: TimelinePost[];
  relatedPosts?: TimelinePost[];
  nextCursor: string | null;
}

const inFlight = new Map<string, Promise<PostDetail>>();

export function loadPostDetail(
  accountId: string,
  postId: string,
  language: Locale = "ja",
  replySort: ReplySort = "relevance",
  cursor?: string | null,
): Promise<PostDetail> {
  const key = `${accountId}:${postId}:${language}:${replySort}:${cursor ?? "first"}`;
  const existing = inFlight.get(key);
  if (existing !== undefined) return existing;
  const params = new URLSearchParams({
    accountId,
    language,
    replySort,
  });
  if (cursor !== undefined && cursor !== null && cursor.length > 0) {
    params.set("cursor", cursor);
  }
  const request = fetch(`/api/v1/posts/${encodeURIComponent(postId)}?${params}`)
    .then(async (response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return (await response.json()) as PostDetail;
    })
    .finally(() => inFlight.delete(key));
  inFlight.set(key, request);
  return request;
}
