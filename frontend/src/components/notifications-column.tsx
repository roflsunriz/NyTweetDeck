import { useCallback, useEffect, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";
import { defaultDisplayPreferences, type DisplayPreferences } from "../model/layout";
import { PostCard, type TimelinePost } from "./post-card";

interface NotificationItem {
  id: string;
  text: string;
  url: string;
  imageUrls: string[];
}

interface NotificationPage {
  notifications: NotificationItem[];
  posts: TimelinePost[];
  nextCursor: string | null;
}

export function NotificationsColumn({
  accountId,
  translation,
  display = defaultDisplayPreferences,
}: {
  accountId: string | null;
  translation: Translation;
  display?: DisplayPreferences;
}) {
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [posts, setPosts] = useState<TimelinePost[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const loadingRef = useRef(false);
  const loadMoreRef = useRef<HTMLButtonElement | null>(null);

  const load = useCallback(
    async (nextCursor?: string) => {
      if (accountId === null || loadingRef.current) {
        return;
      }
      loadingRef.current = true;
      setLoading(true);
      setError(false);
      try {
        const params = new URLSearchParams({ accountId });
        if (nextCursor !== undefined) {
          params.set("cursor", nextCursor);
        }
        const response = await fetch(`/api/v1/notifications?${params}`);
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        const page = (await response.json()) as NotificationPage;
        setNotifications((current) =>
          nextCursor === undefined
            ? page.notifications
            : [
                ...current,
                ...page.notifications.filter(
                  (notification) => !current.some((item) => item.id === notification.id),
                ),
              ],
        );
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
        setError(true);
      } finally {
        loadingRef.current = false;
        setLoading(false);
      }
    },
    [accountId],
  );

  useEffect(() => {
    setNotifications([]);
    setPosts([]);
    setCursor(null);
    void load();
  }, [load]);

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
      <div className="column-message">
        <strong>{translation.loginRequired}</strong>
        <p>{translation.loginRequiredDescription}</p>
      </div>
    );
  }
  if (loading && notifications.length === 0 && posts.length === 0) {
    return <div className="column-message">{translation.loading}</div>;
  }
  if (error && notifications.length === 0 && posts.length === 0) {
    return (
      <div className="column-message">
        <strong>{translation.notificationLoadError}</strong>
        <button className="secondary-button" type="button" onClick={() => load()}>
          {translation.retry}
        </button>
      </div>
    );
  }
  if (notifications.length === 0 && posts.length === 0) {
    return <div className="column-message">{translation.noNotifications}</div>;
  }
  return (
    <div className="notification-list">
      {notifications.map((notification) => (
        <a
          key={notification.id}
          className="notification-item"
          href={notification.url}
          target="_blank"
          rel="noreferrer"
        >
          <div className="notification-avatars">
            {notification.imageUrls.slice(0, 4).map((imageUrl) => (
              <img key={imageUrl} src={imageUrl} alt="" loading="lazy" />
            ))}
          </div>
          <strong>{notification.text || translation.notifications}</strong>
        </a>
      ))}
      {posts.map((post) => (
        <PostCard
          key={post.id}
          post={post}
          accountId={accountId}
          translation={translation}
          display={display}
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
      {error && <p className="inline-error">{translation.notificationLoadError}</p>}
    </div>
  );
}
