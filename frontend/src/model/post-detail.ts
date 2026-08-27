import type { TimelinePost } from "../components/post-card";
import type { Locale } from "./layout";

export interface PostDetail {
  post: TimelinePost;
  replies: TimelinePost[];
  nextCursor: string | null;
}

const inFlight = new Map<string, Promise<PostDetail>>();

export function loadPostDetail(
  accountId: string,
  postId: string,
  language: Locale = "ja",
): Promise<PostDetail> {
  const key = `${accountId}:${postId}:${language}`;
  const existing = inFlight.get(key);
  if (existing !== undefined) return existing;
  const request = fetch(
    `/api/v1/posts/${encodeURIComponent(postId)}?accountId=${encodeURIComponent(accountId)}&language=${encodeURIComponent(language)}`,
  )
    .then(async (response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return (await response.json()) as PostDetail;
    })
    .finally(() => inFlight.delete(key));
  inFlight.set(key, request);
  return request;
}
