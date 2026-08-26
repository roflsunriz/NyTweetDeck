import { useCallback, useEffect, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";

interface DirectMessage {
  id: string;
  conversationId: string | null;
  senderId: string;
  senderName: string | null;
  senderUsername: string | null;
  senderAvatarUrl: string | null;
  text: string;
  timestamp: number;
}

interface DirectMessagePage {
  messages: DirectMessage[];
  nextCursor: string | null;
}

interface DirectMessageColumnProps {
  accountId: string | null;
  translation: Translation;
  subscriptionId?: string;
}

export function DirectMessageColumn({
  accountId,
  translation,
  subscriptionId = "direct-messages",
}: DirectMessageColumnProps) {
  const [messages, setMessages] = useState<DirectMessage[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [liveError, setLiveError] = useState(false);
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
        if (nextCursor !== undefined) {
          params.set("cursor", nextCursor);
        }
        const response = await fetch(`/api/v1/messages?${params}`);
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        const page = (await response.json()) as DirectMessagePage;
        setMessages((current) =>
          nextCursor === undefined
            ? page.messages
            : [
                ...current,
                ...page.messages.filter(
                  (message) => !current.some((item) => item.id === message.id),
                ),
              ],
        );
        setCursor(page.nextCursor);
      } catch {
        setError(translation.messageLoadError);
      } finally {
        loadingRef.current = false;
        setLoading(false);
      }
    },
    [accountId, translation.messageLoadError],
  );

  useEffect(() => {
    setMessages([]);
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
        if (update.reason === "live:dm_update") {
          setLiveError(false);
          void load();
        }
      } catch {
        // Ignore unrelated local events for the DM inbox.
      }
    };
    source.addEventListener("timeline-update", handleUpdate);
    return () => {
      source.removeEventListener("timeline-update", handleUpdate);
      source.close();
    };
  }, [accountId, load]);

  useEffect(() => {
    if (accountId === null) {
      return;
    }
    const controller = new AbortController();
    const id = `messages:${subscriptionId}`;
    void fetch(`/api/v1/live/subscriptions/${encodeURIComponent(id)}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ accountId, postIds: [], directMessages: true }),
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
      void fetch(`/api/v1/live/subscriptions/${encodeURIComponent(id)}?${params}`, {
        method: "DELETE",
        keepalive: true,
      });
    };
  }, [accountId, subscriptionId]);

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
  if (loading && messages.length === 0) {
    return <div className="column-message">{translation.loading}</div>;
  }
  if (error !== null && messages.length === 0) {
    return (
      <div className="column-message">
        <strong>{error}</strong>
        <button className="secondary-button" type="button" onClick={() => load()}>
          {translation.retry}
        </button>
      </div>
    );
  }
  if (messages.length === 0) {
    return <div className="column-message">{translation.noMessages}</div>;
  }
  return (
    <div className="direct-message-list">
      {messages.map((message) => (
        <article className="direct-message" key={message.id}>
          {message.senderAvatarUrl !== null ? (
            <img src={message.senderAvatarUrl} alt="" loading="lazy" />
          ) : (
            <span className="message-avatar-placeholder" aria-hidden="true" />
          )}
          <div>
            <header>
              <strong>{message.senderName ?? message.senderId}</strong>
              {message.senderUsername !== null && <span>@{message.senderUsername}</span>}
            </header>
            <p>{message.text}</p>
          </div>
        </article>
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
    </div>
  );
}
