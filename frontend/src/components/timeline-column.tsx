import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";
import type { ColumnConfig, Locale } from "../model/layout";
import { defaultDisplayPreferences, type DisplayPreferences } from "../model/layout";
import { fetchWithTimeout } from "../model/fetch-with-timeout";
import {
  createTimelineCacheKey,
  mergeRefreshedPosts,
  type TimelineMemoryCache,
  useTimelineCache,
} from "../model/timeline-cache";
import type { TimelineAuthor, TimelinePage, TimelinePost } from "../model/timeline";
import { sortTimelinePosts } from "../model/timeline-sort";
import { PostCard } from "./post-card";
import { PostDetailDialog } from "./post-detail-dialog";
import {
  createDefaultPostFilter,
  filterPosts,
  type PostFilter,
  PostFilterBar,
} from "./post-filter";
import { useManualRefreshAtTop } from "./use-manual-refresh-at-top";
import { UserProfileDialog } from "./user-profile-dialog";

interface TimelineUpdate {
  reason?: string;
  postId?: string | null;
  replyCount?: number | null;
  repostCount?: number | null;
  quoteCount?: number | null;
  likeCount?: number | null;
  bookmarkCount?: number | null;
  viewCount?: number | null;
}

interface NewPostNotification {
  count: number;
  authors: TimelineAuthor[];
}

interface ViewportAnchor {
  postId: string | null;
  postTop: number;
  scrollHeight: number;
  scrollTop: number;
}

type TimelineLoadMode = "replace" | "preserve-viewport";

interface TimelineColumnProps {
  column: ColumnConfig;
  accountId: string | null;
  translation: Translation;
  display?: DisplayPreferences;
  locale?: Locale;
  requestTimeoutMilliseconds?: number;
  refreshMinimumMilliseconds?: number;
  refreshMaximumMilliseconds?: number;
  refreshGlobalGapMilliseconds?: number;
  autoRefreshTimelines?: boolean;
}

let nextTimelineRefreshSlot = 0;

export function TimelineColumn({ ...props }: TimelineColumnProps) {
  const timelineCache = useTimelineCache();
  const locale = props.locale ?? "ja";
  const sort = props.column.sort ?? "latest";
  const cacheKey =
    props.accountId === null
      ? null
      : createTimelineCacheKey(props.accountId, props.column, locale, sort);
  return (
    <TimelineColumnContent
      key={cacheKey ?? "logged-out"}
      {...props}
      timelineCache={timelineCache}
      timelineCacheKey={cacheKey}
    />
  );
}

function TimelineColumnContent({
  column,
  accountId,
  translation,
  display = defaultDisplayPreferences,
  locale = "ja",
  requestTimeoutMilliseconds = 15_000,
  refreshMinimumMilliseconds = 60_000,
  refreshMaximumMilliseconds = 300_000,
  refreshGlobalGapMilliseconds = 15_000,
  autoRefreshTimelines = true,
  timelineCache,
  timelineCacheKey,
}: TimelineColumnProps & {
  timelineCache: TimelineMemoryCache;
  timelineCacheKey: string | null;
}) {
  const cachedPage = timelineCacheKey === null ? null : timelineCache.read(timelineCacheKey);
  const [posts, setPosts] = useState<TimelinePost[]>(() => cachedPage?.posts ?? []);
  const [cursor, setCursor] = useState<string | null>(() => cachedPage?.nextCursor ?? null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedPostId, setSelectedPostId] = useState<string | null>(null);
  const [liveError, setLiveError] = useState(false);
  const [postFilter, setPostFilter] = useState<PostFilter>(createDefaultPostFilter);
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const [newPostNotification, setNewPostNotification] = useState<NewPostNotification | null>(null);
  const loadingRef = useRef(false);
  const postsRef = useRef(posts);
  const loadMoreRef = useRef<HTMLButtonElement | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const viewportAnchorRef = useRef<ViewportAnchor | null>(null);

  const updatePosts = useCallback(
    (update: (current: TimelinePost[]) => TimelinePost[]) => {
      setPosts((current) => {
        const next = update(current);
        postsRef.current = next;
        return next;
      });
      if (timelineCacheKey !== null) timelineCache.updatePosts(timelineCacheKey, update);
    },
    [timelineCache, timelineCacheKey],
  );

  const load = useCallback(
    async (nextCursor?: string, mode: TimelineLoadMode = "replace") => {
      if (accountId === null) {
        return "skipped" as const;
      }
      if (loadingRef.current) {
        return "skipped" as const;
      }
      const hadPosts = postsRef.current.length > 0;
      const showLoading = nextCursor !== undefined || !hadPosts;
      loadingRef.current = true;
      if (showLoading) setLoading(true);
      try {
        const params = new URLSearchParams({ accountId, language: locale });
        params.set("sort", column.sort ?? "latest");
        if (column.target !== null) {
          params.set("target", column.target);
        }
        if (nextCursor !== undefined) {
          params.set("cursor", nextCursor);
        }
        const kind = timelineKind(column.kind);
        const response = await fetchWithTimeout(
          `/api/v1/timelines/${kind}?${params}`,
          {},
          requestTimeoutMilliseconds,
        );
        if (!response.ok) {
          throw new TimelineHttpError(response.status, await readProblemDetail(response));
        }
        const page = (await response.json()) as TimelinePage;
        if (nextCursor === undefined) {
          const preservingViewport = mode === "preserve-viewport" && postsRef.current.length > 0;
          const changed =
            timelineCacheKey === null ||
            (preservingViewport
              ? timelineCache.mergeFirstPage(timelineCacheKey, page)
              : timelineCache.writeFirstPage(timelineCacheKey, page));
          if (changed) {
            if (preservingViewport) {
              const current = postsRef.current;
              viewportAnchorRef.current = captureViewportAnchor(scrollRef.current);
              const merged = mergeRefreshedPosts(current, page.posts);
              const newPosts = merged.slice(0, merged.length - current.length);
              postsRef.current = merged;
              setPosts(merged);
              if (newPosts.length > 0) {
                setNewPostNotification((notification) =>
                  mergeNewPostNotification(notification, newPosts),
                );
              }
            } else {
              viewportAnchorRef.current = null;
              postsRef.current = page.posts;
              setPosts(page.posts);
              setCursor(page.nextCursor);
              setNewPostNotification(null);
            }
          }
          setError(null);
          return changed ? ("changed" as const) : ("unchanged" as const);
        }
        const next = [
          ...postsRef.current,
          ...page.posts.filter((post) => !postsRef.current.some((item) => item.id === post.id)),
        ];
        postsRef.current = next;
        setPosts(next);
        if (timelineCacheKey !== null) timelineCache.appendPage(timelineCacheKey, page);
        setCursor(page.nextCursor);
        setError(null);
        return "unchanged" as const;
      } catch (loadError) {
        if (!hadPosts || nextCursor !== undefined) {
          const detail = loadError instanceof TimelineHttpError ? loadError.detail : null;
          setError(
            detail === null
              ? translation.timelineLoadError
              : `${translation.timelineLoadError} ${detail}`,
          );
        }
        return "failed" as const;
      } finally {
        loadingRef.current = false;
        if (showLoading) setLoading(false);
      }
    },
    [
      accountId,
      column.kind,
      column.target,
      column.sort,
      locale,
      requestTimeoutMilliseconds,
      timelineCache,
      timelineCacheKey,
      translation.timelineLoadError,
    ],
  );
  const { manualRefreshing, manualRefreshHandlers } = useManualRefreshAtTop(() => load());

  useLayoutEffect(() => {
    const anchor = viewportAnchorRef.current;
    const scroll = scrollRef.current;
    if (anchor === null || scroll === null) return;
    viewportAnchorRef.current = null;
    restoreViewportAnchor(scroll, anchor);
  });

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (accountId === null || !autoRefreshTimelines) {
      return;
    }
    let stopped = false;
    let timeout: ReturnType<typeof setTimeout> | undefined;
    let refreshDelay = refreshMinimumMilliseconds;
    const maximumDelay = Math.max(refreshMinimumMilliseconds, refreshMaximumMilliseconds);

    const schedule = (requestedDelay: number) => {
      if (stopped) {
        return;
      }
      timeout = setTimeout(
        () => {
          const slotDelay = reserveTimelineRefreshDelay(refreshGlobalGapMilliseconds);
          timeout = setTimeout(() => void refresh(), slotDelay);
        },
        Math.max(0, requestedDelay),
      );
    };
    const refresh = async () => {
      if (document.visibilityState === "hidden") {
        return;
      }
      const result = await load(undefined, "preserve-viewport");
      if (result === "changed") {
        refreshDelay = refreshMinimumMilliseconds;
      } else if (result === "unchanged" || result === "failed") {
        refreshDelay = Math.min(maximumDelay, Math.max(1, refreshDelay * 2));
      }
      schedule(refreshDelay);
    };
    const handleVisibilityChange = () => {
      if (document.visibilityState !== "hidden") {
        if (timeout !== undefined) {
          clearTimeout(timeout);
        }
        schedule(0);
      }
    };

    document.addEventListener("visibilitychange", handleVisibilityChange);
    schedule(refreshDelay);
    return () => {
      stopped = true;
      if (timeout !== undefined) {
        clearTimeout(timeout);
      }
      document.removeEventListener("visibilitychange", handleVisibilityChange);
    };
  }, [
    accountId,
    load,
    refreshGlobalGapMilliseconds,
    refreshMaximumMilliseconds,
    refreshMinimumMilliseconds,
    autoRefreshTimelines,
  ]);

  useEffect(() => {
    if (accountId === null || typeof EventSource === "undefined") {
      return;
    }
    const params = new URLSearchParams({ accountId });
    const source = new EventSource(`/api/v1/events/timeline?${params}`);
    const handleUpdate = (event: MessageEvent<string>) => {
      let update: TimelineUpdate;
      try {
        update = JSON.parse(event.data) as TimelineUpdate;
      } catch {
        return;
      }
      if (update.reason === "live:error") {
        setLiveError(true);
        return;
      }
      if (update.reason?.startsWith("live:") === true) {
        setLiveError(false);
        if (update.reason === "live:tweet_engagement" && update.postId != null) {
          updatePosts((current) =>
            current.map((post) =>
              post.id === update.postId ? applyEngagementUpdate(post, update) : post,
            ),
          );
        }
        return;
      }
      const action = update.reason;
      if (update.postId != null && isPostAction(action)) {
        updatePosts((current) =>
          current.map((post) => (post.id === update.postId ? applyPostAction(post, action) : post)),
        );
      }
      if (update.reason === "bookmark" || update.reason === "removeBookmark") {
        if (autoRefreshTimelines && column.kind === "history") void load();
        return;
      }
      if (
        autoRefreshTimelines &&
        (update.reason === "create" || update.reason === "reply" || update.reason === "quote")
      ) {
        void load(undefined, "preserve-viewport");
      }
    };
    source.addEventListener("timeline-update", handleUpdate);
    return () => {
      source.removeEventListener("timeline-update", handleUpdate);
      source.close();
    };
  }, [accountId, autoRefreshTimelines, column.kind, load, updatePosts]);

  const livePostIds = posts
    .slice(0, 100)
    .map((post) => post.id)
    .join(",");

  useEffect(() => {
    if (accountId === null || livePostIds.length === 0) {
      return;
    }
    const controller = new AbortController();
    const subscriberId = `timeline:${column.id}`;
    void fetch(`/api/v1/live/subscriptions/${encodeURIComponent(subscriberId)}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        accountId,
        postIds: livePostIds.split(","),
        directMessages: false,
      }),
      signal: controller.signal,
    })
      .then((response) => {
        if (!response.ok) {
          setLiveError(true);
        }
      })
      .catch((subscriptionError) => {
        if (
          !(subscriptionError instanceof DOMException && subscriptionError.name === "AbortError")
        ) {
          setLiveError(true);
        }
      });
    return () => {
      controller.abort();
    };
  }, [accountId, column.id, livePostIds]);

  useEffect(() => {
    if (accountId === null) {
      return;
    }
    const subscriberId = `timeline:${column.id}`;
    return () => {
      const params = new URLSearchParams({ accountId });
      void fetch(`/api/v1/live/subscriptions/${encodeURIComponent(subscriberId)}?${params}`, {
        method: "DELETE",
        keepalive: true,
      });
    };
  }, [accountId, column.id]);

  useEffect(() => {
    const target = loadMoreRef.current;
    if (target === null || cursor === null || typeof IntersectionObserver === "undefined") {
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          void load(cursor);
        }
      },
      { rootMargin: "240px 0px" },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [cursor, load]);

  if (accountId === null) {
    return (
      <ColumnMessage
        title={translation.loginRequired}
        body={translation.loginRequiredDescription}
      />
    );
  }
  if (error !== null && posts.length === 0) {
    return (
      <div className="column-message">
        <strong>{error}</strong>
        <button className="secondary-button" type="button" onClick={() => load()}>
          {translation.retry}
        </button>
      </div>
    );
  }
  if (loading && posts.length === 0) {
    return <ColumnMessage title={translation.loading} />;
  }
  if (posts.length === 0) {
    return <ColumnMessage title={translation.noPosts} />;
  }
  const visiblePosts = sortTimelinePosts(
    filterPosts(posts, postFilter),
    column.kind,
    column.sort ?? "latest",
  );
  return (
    <div
      ref={scrollRef}
      className="timeline-content refreshable-scroll"
      data-testid="timeline-scroll"
      {...manualRefreshHandlers}
    >
      <div className="new-post-notification-layer">
        {newPostNotification !== null && (
          <button
            className="new-post-notification"
            type="button"
            aria-label={translation.showNewPosts(newPostNotification.count)}
            onClick={() => {
              if (scrollRef.current !== null) scrollRef.current.scrollTop = 0;
              setNewPostNotification(null);
            }}
          >
            <span>{translation.newPosts}</span>
            <span className="new-post-avatars" aria-hidden="true">
              {newPostNotification.authors.map((author) => (
                <span className="new-post-avatar" key={author.id} title={`@${author.username}`}>
                  {author.avatarUrl === null ? (
                    avatarFallback(author)
                  ) : (
                    <img src={author.avatarUrl} alt="" />
                  )}
                </span>
              ))}
            </span>
          </button>
        )}
      </div>
      {manualRefreshing && (
        <div className="manual-refresh-status" role="status">
          {translation.loading}
        </div>
      )}
      <PostFilterBar value={postFilter} translation={translation} onChange={setPostFilter} />
      {visiblePosts.length === 0 && <ColumnMessage title={translation.noFilteredPosts} />}
      {visiblePosts.map((post) => (
        <PostCard
          key={post.id}
          post={post}
          accountId={accountId}
          translation={translation}
          display={display}
          onOpen={() => setSelectedPostId(post.id)}
          onOpenQuotedPost={setSelectedPostId}
          onOpenUser={setSelectedUserId}
        />
      ))}
      {cursor !== null && (
        <button
          ref={loadMoreRef}
          className="load-more-button"
          type="button"
          disabled={loading}
          onClick={() => load(cursor)}
        >
          {loading ? translation.loading : translation.loadMore}
        </button>
      )}
      {error !== null && <p className="inline-error">{error}</p>}
      {liveError && <p className="inline-warning">{translation.liveUpdateUnavailable}</p>}
      {selectedPostId !== null && (
        <PostDetailDialog
          postId={selectedPostId}
          accountId={accountId}
          translation={translation}
          display={display}
          onClose={() => setSelectedPostId(null)}
          onOpenPost={setSelectedPostId}
          onOpenUser={setSelectedUserId}
        />
      )}
      {selectedUserId !== null && (
        <UserProfileDialog
          userId={selectedUserId}
          accountId={accountId}
          translation={translation}
          display={display}
          onClose={() => setSelectedUserId(null)}
        />
      )}
    </div>
  );
}

function mergeNewPostNotification(
  current: NewPostNotification | null,
  posts: TimelinePost[],
): NewPostNotification {
  const authors = current?.authors.slice() ?? [];
  const authorIds = new Set(authors.map((author) => author.id));
  for (const post of posts) {
    if (authors.length >= 5) break;
    if (authorIds.has(post.author.id)) continue;
    authorIds.add(post.author.id);
    authors.push(post.author);
  }
  return { count: (current?.count ?? 0) + posts.length, authors };
}

function avatarFallback(author: TimelineAuthor): string {
  return author.displayName.trim().charAt(0) || author.username.trim().charAt(0) || "?";
}

function captureViewportAnchor(scroll: HTMLDivElement | null): ViewportAnchor | null {
  if (scroll === null) return null;
  const scrollBounds = scroll.getBoundingClientRect();
  const viewportCenter = (scrollBounds.top + scrollBounds.bottom) / 2;
  const visiblePosts = Array.from(scroll.querySelectorAll<HTMLElement>(".post-card"))
    .map((post) => ({ post, bounds: post.getBoundingClientRect() }))
    .filter(({ bounds }) => bounds.bottom > scrollBounds.top && bounds.top < scrollBounds.bottom);
  const readingPost = visiblePosts.reduce<(typeof visiblePosts)[number] | undefined>(
    (closest, candidate) => {
      if (closest === undefined) return candidate;
      const candidateCenter = (candidate.bounds.top + candidate.bounds.bottom) / 2;
      const closestCenter = (closest.bounds.top + closest.bounds.bottom) / 2;
      return Math.abs(candidateCenter - viewportCenter) < Math.abs(closestCenter - viewportCenter)
        ? candidate
        : closest;
    },
    undefined,
  );
  return {
    postId: readingPost?.post.dataset.postId ?? null,
    postTop: readingPost?.bounds.top ?? scrollBounds.top,
    scrollHeight: scroll.scrollHeight,
    scrollTop: scroll.scrollTop,
  };
}

function restoreViewportAnchor(scroll: HTMLDivElement, anchor: ViewportAnchor): void {
  const anchoredPost = Array.from(scroll.querySelectorAll<HTMLElement>(".post-card")).find(
    (post) => post.dataset.postId === anchor.postId,
  );
  const heightDelta = scroll.scrollHeight - anchor.scrollHeight;
  if (anchoredPost === undefined) {
    scroll.scrollTop = Math.max(0, anchor.scrollTop + heightDelta);
    return;
  }
  const offsetDelta = anchoredPost.getBoundingClientRect().top - anchor.postTop;
  if (Math.abs(offsetDelta) < 0.5) return;
  scroll.scrollTop = Math.max(0, scroll.scrollTop + offsetDelta);
}

class TimelineHttpError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string | null,
  ) {
    super(`HTTP ${status}`);
  }
}

async function readProblemDetail(response: Response): Promise<string | null> {
  try {
    const problem = (await response.json()) as { detail?: unknown };
    return typeof problem.detail === "string" && problem.detail.length > 0 ? problem.detail : null;
  } catch {
    return null;
  }
}

function timelineKind(kind: ColumnConfig["kind"]): string {
  switch (kind) {
    case "home":
      return "homeForYou";
    case "following":
      return "homeFollowing";
    case "search":
      return "search";
    case "history":
      return "history";
    case "user":
      return "userPosts";
    case "list":
      return "list";
    case "notifications":
      return "notifications";
    case "trends":
      return "trends";
    case "messages":
      throw new Error("メッセージは専用カラムで表示します。");
  }
}

function applyEngagementUpdate(post: TimelinePost, update: TimelineUpdate): TimelinePost {
  return {
    ...post,
    replyCount: validCount(update.replyCount) ?? post.replyCount,
    repostCount: validCount(update.repostCount) ?? post.repostCount,
    quoteCount: validCount(update.quoteCount) ?? post.quoteCount,
    likeCount: validCount(update.likeCount) ?? post.likeCount,
    bookmarkCount: validCount(update.bookmarkCount) ?? post.bookmarkCount,
    viewCount: validCount(update.viewCount) ?? post.viewCount,
  };
}

function isPostAction(reason: string | undefined): reason is PostActionReason {
  return (
    reason === "like" ||
    reason === "unlike" ||
    reason === "repost" ||
    reason === "undoRepost" ||
    reason === "bookmark" ||
    reason === "removeBookmark"
  );
}

type PostActionReason = "like" | "unlike" | "repost" | "undoRepost" | "bookmark" | "removeBookmark";

function applyPostAction(post: TimelinePost, action: PostActionReason): TimelinePost {
  switch (action) {
    case "like":
      return post.liked ? post : { ...post, liked: true, likeCount: post.likeCount + 1 };
    case "unlike":
      return post.liked
        ? { ...post, liked: false, likeCount: Math.max(0, post.likeCount - 1) }
        : post;
    case "repost":
      return post.reposted ? post : { ...post, reposted: true, repostCount: post.repostCount + 1 };
    case "undoRepost":
      return post.reposted
        ? { ...post, reposted: false, repostCount: Math.max(0, post.repostCount - 1) }
        : post;
    case "bookmark":
      return post.bookmarked
        ? post
        : { ...post, bookmarked: true, bookmarkCount: post.bookmarkCount + 1 };
    case "removeBookmark":
      return post.bookmarked
        ? { ...post, bookmarked: false, bookmarkCount: Math.max(0, post.bookmarkCount - 1) }
        : post;
  }
}

function validCount(value: number | null | undefined): number | null {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : null;
}

function reserveTimelineRefreshDelay(globalGap: number): number {
  const now = Date.now();
  const reservedAt = Math.max(now, nextTimelineRefreshSlot);
  nextTimelineRefreshSlot = reservedAt + Math.max(0, globalGap);
  return Math.max(0, reservedAt - now);
}

function ColumnMessage({ title, body }: { title: string; body?: string }) {
  return (
    <div className="column-message">
      <strong>{title}</strong>
      {body !== undefined && <p>{body}</p>}
    </div>
  );
}
