import { Bell, Heart, MessageCircle, Repeat2, UserPlus } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";
import { defaultDisplayPreferences, type DisplayPreferences } from "../model/layout";
import type { Locale } from "../model/layout";
import { Modal } from "./modal";
import { PostCard, type TimelinePost } from "./post-card";
import { PostDetailDialog } from "./post-detail-dialog";
import { filterPosts, type PostFilter, PostFilterBar } from "./post-filter";
import { UserProfileDialog } from "./user-profile-dialog";

interface NotificationItem {
  id: string;
  kind: "like" | "repost" | "reply" | "follow" | "community_note" | "notification";
  text: string;
  detailText: string;
  postId: string | null;
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
  locale = "ja",
}: {
  accountId: string | null;
  translation: Translation;
  display?: DisplayPreferences;
  locale?: Locale;
}) {
  const [notifications, setNotifications] = useState<NotificationItem[]>([]);
  const [posts, setPosts] = useState<TimelinePost[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const [selectedPostId, setSelectedPostId] = useState<string | null>(null);
  const [postFilter, setPostFilter] = useState<PostFilter>("all");
  const [selectedUserId, setSelectedUserId] = useState<string | null>(null);
  const [selectedCommunityNote, setSelectedCommunityNote] = useState<NotificationItem | null>(null);
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
        const params = new URLSearchParams({ accountId, language: locale });
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
    [accountId, locale],
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
      {posts.length > 0 && (
        <PostFilterBar value={postFilter} translation={translation} onChange={setPostFilter} />
      )}
      {notifications.map((notification) => (
        <NotificationEntry
          key={notification.id}
          notification={notification}
          fallbackText={translation.notifications}
          onOpen={(item) => {
            if (item.kind === "community_note") {
              setSelectedCommunityNote(item);
            } else if (item.postId !== null) {
              setSelectedPostId(item.postId);
            }
          }}
        />
      ))}
      {filterPosts(posts, postFilter).map((post) => (
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
      {error && <p className="inline-error">{translation.notificationLoadError}</p>}
      {selectedPostId !== null && accountId !== null && (
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
      {selectedUserId !== null && accountId !== null && (
        <UserProfileDialog
          userId={selectedUserId}
          accountId={accountId}
          translation={translation}
          display={display}
          onClose={() => setSelectedUserId(null)}
        />
      )}
      {selectedCommunityNote !== null && (
        <Modal
          title={translation.communityNoteDetails}
          closeLabel={translation.closeDetail}
          onClose={() => setSelectedCommunityNote(null)}
        >
          <div className="community-note-detail">
            {selectedCommunityNote.imageUrls.length > 0 && (
              <div className="notification-avatars" aria-hidden="true">
                {selectedCommunityNote.imageUrls.slice(0, 4).map((imageUrl) => (
                  <img key={imageUrl} src={imageUrl} alt="" />
                ))}
              </div>
            )}
            <p>{selectedCommunityNote.detailText || selectedCommunityNote.text}</p>
            {selectedCommunityNote.postId !== null && (
              <button
                className="primary-button"
                type="button"
                onClick={() => {
                  const postId = selectedCommunityNote.postId;
                  if (postId !== null) {
                    setSelectedPostId(postId);
                  }
                  setSelectedCommunityNote(null);
                }}
              >
                {translation.viewRelatedPost}
              </button>
            )}
          </div>
        </Modal>
      )}
    </div>
  );
}

function NotificationEntry({
  notification,
  fallbackText,
  onOpen,
}: {
  notification: NotificationItem;
  fallbackText: string;
  onOpen: (notification: NotificationItem) => void;
}) {
  const postId = notification.postId;
  const content = (
    <>
      <span className="notification-icon-slot">
        <NotificationIcon kind={notification.kind} />
      </span>
      <span className="notification-content">
        {notification.imageUrls.length > 0 && (
          <span className="notification-avatars" aria-hidden="true">
            {notification.imageUrls.slice(0, 4).map((imageUrl) => (
              <img key={imageUrl} src={imageUrl} alt="" loading="lazy" />
            ))}
          </span>
        )}
        <strong>{notification.text || fallbackText}</strong>
      </span>
    </>
  );
  if (postId === null && notification.kind !== "community_note") {
    return (
      <article
        className="deck-feed-item notification-item notification-item-static"
        data-notification-kind={notification.kind}
      >
        {content}
      </article>
    );
  }
  return (
    <button
      type="button"
      className="deck-feed-item notification-item"
      data-notification-kind={notification.kind}
      onClick={() => onOpen(notification)}
    >
      {content}
    </button>
  );
}

function NotificationIcon({ kind }: { kind: NotificationItem["kind"] }) {
  const Icon =
    kind === "like"
      ? Heart
      : kind === "repost"
        ? Repeat2
        : kind === "reply"
          ? MessageCircle
          : kind === "follow"
            ? UserPlus
            : Bell;
  return (
    <Icon className={`notification-kind notification-kind-${kind}`} aria-hidden="true" size={20} />
  );
}
