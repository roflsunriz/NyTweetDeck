import { useCallback, useEffect, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";
import type { ColumnConfig } from "../model/layout";
import { defaultDisplayPreferences, type DisplayPreferences } from "../model/layout";
import { PostCard, type TimelinePost } from "./post-card";
import { PostDetailDialog } from "./post-detail-dialog";
import { filterPosts, type PostFilter, PostFilterBar } from "./post-filter";
import { UserProfileDialog } from "./user-profile-dialog";

interface TimelinePage {
  posts: TimelinePost[];
  nextCursor: string | null;
}

interface TimelineColumnProps {
  column: ColumnConfig;
  accountId: string | null;
  translation: Translation;
  display?: DisplayPreferences;
}

export function TimelineColumn({
  column,
  accountId,
  translation,
  display = defaultDisplayPreferences,
}: TimelineColumnProps) {
  const [posts, setPosts] = useState<TimelinePost[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedPostId, setSelectedPostId] = useState<string | null>(null);
  const [liveError, setLiveError] = useState(false);
  const [postFilter, setPostFilter] = useState<PostFilter>("all");
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const [vaultLocked, setVaultLocked] = useState(false);
  const loadingRef = useRef(false);
  const loadMoreRef = useRef<HTMLButtonElement | null>(null);

  const load = useCallback(
    async (nextCursor?: string) => {
      if (accountId === null) {
        return;
      }
      if (loadingRef.current) {
        return;
      }
      loadingRef.current = true;
      setLoading(true);
      setError(null);
      try {
        const params = new URLSearchParams({ accountId });
        if (column.target !== null) {
          params.set("target", column.target);
        }
        if (nextCursor !== undefined) {
          params.set("cursor", nextCursor);
        }
        const kind = timelineKind(column.kind);
        const response = await fetch(`/api/v1/timelines/${kind}?${params}`);
        if (!response.ok) {
          throw new TimelineHttpError(response.status, await readProblemDetail(response));
        }
        const page = (await response.json()) as TimelinePage;
        setPosts((current) =>
          nextCursor === undefined
            ? page.posts
            : [
                ...current,
                ...page.posts.filter((post) => !current.some((item) => item.id === post.id)),
              ],
        );
        setCursor(page.nextCursor);
        setVaultLocked(false);
      } catch (loadError) {
        if (loadError instanceof TimelineHttpError && loadError.status === 423) {
          setVaultLocked(true);
          window.dispatchEvent(new Event("nytweetdeck:vault-locked"));
        } else {
          const detail = loadError instanceof TimelineHttpError ? loadError.detail : null;
          setError(
            detail === null
              ? translation.timelineLoadError
              : `${translation.timelineLoadError} ${detail}`,
          );
        }
      } finally {
        loadingRef.current = false;
        setLoading(false);
      }
    },
    [accountId, column.kind, column.target, translation.timelineLoadError],
  );

  useEffect(() => {
    setPosts([]);
    setCursor(null);
    void load();
  }, [load]);

  useEffect(() => {
    if (accountId === null || typeof EventSource === "undefined") {
      return;
    }
    const params = new URLSearchParams({ accountId });
    const source = new EventSource(`/api/v1/events/timeline?${params}`);
    const handleUpdate = (event: MessageEvent<string>) => {
      try {
        const update = JSON.parse(event.data) as { reason?: string };
        if (update.reason === "live:error") {
          setLiveError(true);
          return;
        }
        if (update.reason?.startsWith("live:") === true) {
          setLiveError(false);
        }
      } catch {
        // Unknown local event data still requests a safe refresh.
      }
      void load();
    };
    source.addEventListener("timeline-update", handleUpdate);
    return () => {
      source.removeEventListener("timeline-update", handleUpdate);
      source.close();
    };
  }, [accountId, load]);

  useEffect(() => {
    if (accountId === null || posts.length === 0) {
      return;
    }
    const controller = new AbortController();
    const subscriberId = `timeline:${column.id}`;
    void fetch(`/api/v1/live/subscriptions/${encodeURIComponent(subscriberId)}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ accountId, postIds: posts.slice(0, 100).map((post) => post.id) }),
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
      const params = new URLSearchParams({ accountId });
      void fetch(`/api/v1/live/subscriptions/${encodeURIComponent(subscriberId)}?${params}`, {
        method: "DELETE",
        keepalive: true,
      });
    };
  }, [accountId, column.id, posts]);

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
  if (vaultLocked) {
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
  const visiblePosts = filterPosts(posts, postFilter);
  return (
    <div className="timeline-content">
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

function ColumnMessage({ title, body }: { title: string; body?: string }) {
  return (
    <div className="column-message">
      <strong>{title}</strong>
      {body !== undefined && <p>{body}</p>}
    </div>
  );
}
