import { fetchWithTimeout } from "./fetch-with-timeout";
import type { TimelinePost } from "../components/post-card";
import type { Locale, ReplySort } from "./layout";
import { PageSnapshots } from "./page-snapshots";

export interface PostDetail {
  post: TimelinePost;
  contextPosts?: TimelinePost[];
  replies: TimelinePost[];
  relatedPosts?: TimelinePost[];
  nextCursor: string | null;
}

const inFlight = new Map<string, Promise<PostDetail>>();
const snapshots = new PageSnapshots();
let nextSpeculativeRequestAt = 0;
let speculativeInFlight = false;
export function clearPostDetailSnapshots(): void {
  snapshots.clear();
  nextSpeculativeRequestAt = 0;
}

function detailKey(
  accountId: string,
  postId: string,
  language: Locale,
  replySort: ReplySort,
): string {
  return JSON.stringify([accountId, postId, language, replySort]);
}

export function readPostDetail(
  accountId: string,
  postId: string,
  language: Locale,
  replySort: ReplySort,
): PostDetail | null {
  return snapshots.read<PostDetail>(detailKey(accountId, postId, language, replySort));
}

/** Intent only: one speculative request at a time, at least 5s apart. */
export function prefetchPostDetail(
  accountId: string,
  postId: string,
  language: Locale,
  replySort: ReplySort,
): void {
  if (
    document.visibilityState === "hidden" ||
    speculativeInFlight ||
    Date.now() < nextSpeculativeRequestAt ||
    readPostDetail(accountId, postId, language, replySort) !== null
  )
    return;
  speculativeInFlight = true;
  nextSpeculativeRequestAt = Date.now() + 5_000;
  void loadPostDetail(accountId, postId, language, replySort)
    .catch(() => {
      /* An explicit open retries; speculative errors never replace visible content. */
    })
    .finally(() => {
      speculativeInFlight = false;
    });
}

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
  const request = fetchWithTimeout(`/api/v1/posts/${encodeURIComponent(postId)}?${params}`)
    .then(async (response) => {
      if (!response.ok) {
        if (response.status === 429) {
          const retry = response.headers.get("retry-after");
          const retryAt =
            retry === null
              ? 0
              : /^\d+(?:\.\d+)?$/u.test(retry)
                ? Date.now() + Number(retry) * 1_000
                : Date.parse(retry);
          const resetAt = Number(response.headers.get("x-rate-limit-reset")) * 1_000;
          nextSpeculativeRequestAt = Math.max(
            Date.now() + 60_000,
            Number.isFinite(retryAt) ? retryAt : 0,
            Number.isFinite(resetAt) ? resetAt : 0,
          );
        }
        throw new Error(`HTTP ${response.status}`);
      }
      const detail = (await response.json()) as PostDetail;
      if (
        (cursor === undefined || cursor === null) &&
        detail.post?.author !== undefined &&
        Array.isArray(detail.replies)
      )
        snapshots.write(detailKey(accountId, postId, language, replySort), detail);
      return detail;
    })
    .finally(() => inFlight.delete(key));
  inFlight.set(key, request);
  return request;
}
