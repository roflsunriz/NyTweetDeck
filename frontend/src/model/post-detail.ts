import type { TimelinePost } from "../components/post-card";

export interface PostDetail {
  post: TimelinePost;
  replies: TimelinePost[];
  nextCursor: string | null;
}

const inFlight = new Map<string, Promise<PostDetail>>();

export function loadPostDetail(accountId: string, postId: string): Promise<PostDetail> {
  const key = `${accountId}:${postId}`;
  const existing = inFlight.get(key);
  if (existing !== undefined) return existing;
  const request = fetch(
    `/api/v1/posts/${encodeURIComponent(postId)}?accountId=${encodeURIComponent(accountId)}`,
  )
    .then(async (response) => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return (await response.json()) as PostDetail;
    })
    .finally(() => inFlight.delete(key));
  inFlight.set(key, request);
  return request;
}
