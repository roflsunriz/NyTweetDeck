import { useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import { Modal } from "./modal";

export interface NotificationActor {
  id: string | null;
  username: string | null;
  displayName: string | null;
  avatarUrl: string | null;
}

export function FollowNotificationUsersDialog({
  actors,
  fallbackText,
  fallbackImageUrls,
  accountId,
  translation,
  onOpenUser,
  onClose,
}: {
  actors: NotificationActor[];
  fallbackText: string;
  fallbackImageUrls: string[];
  accountId: string;
  translation: Translation;
  onOpenUser: (userId: string) => void;
  onClose: () => void;
}) {
  const [users, setUsers] = useState(() => fallbackActors(actors, fallbackText, fallbackImageUrls));

  useEffect(() => {
    let active = true;
    const initial = fallbackActors(actors, fallbackText, fallbackImageUrls);
    setUsers(initial);
    const resolvable = initial.filter((actor) => actor.id === null && actor.username !== null);
    if (resolvable.length === 0) return () => undefined;
    void Promise.all(
      initial.map(async (actor) => {
        if (actor.id !== null || actor.username === null) return actor;
        try {
          const params = new URLSearchParams({ accountId, username: actor.username });
          const response = await fetch(`/api/v1/users/resolve?${params}`);
          if (!response.ok) return actor;
          return (await response.json()) as NotificationActor;
        } catch {
          return actor;
        }
      }),
    ).then((resolved) => {
      if (active) setUsers(resolved);
    });
    return () => {
      active = false;
    };
  }, [accountId, actors, fallbackImageUrls, fallbackText]);

  return (
    <Modal title={translation.followersCount} closeLabel={translation.close} onClose={onClose}>
      <div className="notification-user-list" data-testid="follow-notification-users">
        {users.map((user, index) => {
          const label =
            user.displayName ?? (user.username === null ? fallbackText : `@${user.username}`);
          return (
            <button
              className="notification-user-item"
              type="button"
              key={user.id ?? user.username ?? `${user.avatarUrl ?? "unknown"}-${index}`}
              disabled={user.id === null}
              onClick={() => {
                if (user.id !== null) onOpenUser(user.id);
              }}
            >
              {user.avatarUrl === null ? (
                <span className="notification-user-avatar-placeholder" aria-hidden="true" />
              ) : (
                <img src={user.avatarUrl} alt="" loading="lazy" />
              )}
              <span>
                <strong>{label}</strong>
                {user.username !== null && <small>@{user.username}</small>}
              </span>
            </button>
          );
        })}
      </div>
    </Modal>
  );
}

function fallbackActors(
  actors: NotificationActor[],
  fallbackText: string,
  fallbackImageUrls: string[],
): NotificationActor[] {
  if (actors.length > 0) return actors;
  if (fallbackImageUrls.length > 0) {
    return fallbackImageUrls.map((avatarUrl, index) => ({
      id: null,
      username: null,
      displayName: index === 0 ? fallbackText : null,
      avatarUrl,
    }));
  }
  return [{ id: null, username: null, displayName: fallbackText, avatarUrl: null }];
}
