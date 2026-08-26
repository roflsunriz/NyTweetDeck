import { useCallback, useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import type { ColumnConfig } from "../model/layout";
import { PostCard, type TimelinePost } from "./post-card";
import { PostDetailDialog } from "./post-detail-dialog";

interface TimelinePage {
  posts: TimelinePost[];
  nextCursor: string | null;
}

interface TimelineColumnProps {
  column: ColumnConfig;
  accountId: string | null;
  translation: Translation;
}

export function TimelineColumn({ column, accountId, translation }: TimelineColumnProps) {
  const [posts, setPosts] = useState<TimelinePost[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedPostId, setSelectedPostId] = useState<string | null>(null);

  const load = useCallback(
    async (nextCursor?: string) => {
      if (accountId === null) {
        return;
      }
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
          throw new Error(`HTTP ${response.status}`);
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
      } catch {
        setError(translation.timelineLoadError);
      } finally {
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
  return (
    <div className="timeline-content">
      {posts.map((post) => (
        <PostCard
          key={post.id}
          post={post}
          accountId={accountId}
          translation={translation}
          onOpen={() => setSelectedPostId(post.id)}
        />
      ))}
      {cursor !== null && (
        <button
          className="load-more-button"
          type="button"
          disabled={loading}
          onClick={() => load(cursor)}
        >
          {loading ? translation.loading : translation.loadMore}
        </button>
      )}
      {error !== null && <p className="inline-error">{error}</p>}
      {selectedPostId !== null && (
        <PostDetailDialog
          postId={selectedPostId}
          accountId={accountId}
          translation={translation}
          onClose={() => setSelectedPostId(null)}
        />
      )}
    </div>
  );
}

function timelineKind(kind: ColumnConfig["kind"]): string {
  switch (kind) {
    case "home":
      return "homeForYou";
    case "history":
      return "history";
    case "user":
      return "userPosts";
    case "list":
      return "list";
    case "notifications":
      return "notifications";
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
