import { MoreHorizontal } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";

type UserMenuAction = "follow" | "mute" | "block";
type ListMenuAction = "add" | "remove";

export function PostMenu({
  accountId,
  userId,
  postUrl,
  translation,
  onHide,
}: {
  accountId: string;
  userId: string;
  postUrl: string;
  translation: Translation;
  onHide: () => void;
}) {
  const menuId = useId();
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!open) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [open]);
  const [pendingUserActions, setPendingUserActions] = useState<ReadonlySet<string>>(
    () => new Set(),
  );
  const pendingUserActionsRef = useRef(new Set<string>());
  const [completedUserActions, setCompletedUserActions] = useState<ReadonlySet<string>>(
    () => new Set(),
  );
  const completedUserActionsRef = useRef(new Set<string>());
  const [listOptimisticAction, setListOptimisticAction] = useState<{
    id: string;
    action: ListMenuAction;
  } | null>(null);
  const listOptimisticActionRef = useRef(listOptimisticAction);
  const [listPendingCount, setListPendingCount] = useState(0);
  const listQueueRef = useRef<Promise<void>>(Promise.resolve());
  const [error, setError] = useState(false);
  const [listEditor, setListEditor] = useState(false);
  const [listId, setListId] = useState("");
  const links = [
    [translation.postActivity, `${postUrl}/analytics`],
    [translation.embedPost, `https://publish.twitter.com/#query=${encodeURIComponent(postUrl)}`],
    [
      translation.reportPost,
      `https://x.com/i/safety/report_story?tweet_id=${postUrl.split("/").at(-1)}`,
    ],
    [translation.requestCommunityNote, "https://x.com/i/communitynotes"],
  ] as const;

  const userAction = (action: UserMenuAction) => {
    if (action === "block" && !window.confirm(translation.confirmBlock)) return;
    if (pendingUserActionsRef.current.has(action) || completedUserActionsRef.current.has(action)) {
      return;
    }
    pendingUserActionsRef.current.add(action);
    completedUserActionsRef.current.add(action);
    setPendingUserActions(new Set(pendingUserActionsRef.current));
    setCompletedUserActions(new Set(completedUserActionsRef.current));
    setError(false);
    void fetch(
      `/api/v1/users/${encodeURIComponent(userId)}/actions/${action}?accountId=${encodeURIComponent(accountId)}`,
      { method: "POST" },
    )
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
      })
      .catch(() => {
        completedUserActionsRef.current.delete(action);
        setCompletedUserActions(new Set(completedUserActionsRef.current));
        setError(true);
      })
      .finally(() => {
        pendingUserActionsRef.current.delete(action);
        setPendingUserActions(new Set(pendingUserActionsRef.current));
      });
  };

  const listAction = (action: ListMenuAction) => {
    if (!/^\d{1,30}$/.test(listId)) {
      setError(true);
      return;
    }
    const requested = { id: listId, action };
    listOptimisticActionRef.current = requested;
    setListOptimisticAction(requested);
    setListPendingCount((current) => current + 1);
    setError(false);
    listQueueRef.current = listQueueRef.current
      .catch(() => undefined)
      .then(async () => {
        try {
          const response = await fetch(
            `/api/v1/users/${encodeURIComponent(userId)}/lists/${encodeURIComponent(requested.id)}/${requested.action}?accountId=${encodeURIComponent(accountId)}`,
            { method: "POST" },
          );
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
        } catch {
          if (
            listOptimisticActionRef.current?.id === requested.id &&
            listOptimisticActionRef.current.action === requested.action
          ) {
            listOptimisticActionRef.current = null;
            setListOptimisticAction(null);
            setError(true);
          }
        } finally {
          setListPendingCount((current) => Math.max(0, current - 1));
        }
      });
  };

  return (
    <div className="post-overflow">
      <button
        type="button"
        className="post-menu-trigger"
        aria-controls={menuId}
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={translation.postMenu}
        onClick={() => setOpen((current) => !current)}
      >
        <MoreHorizontal aria-hidden="true" size={17} />
      </button>
      {open && (
        <div id={menuId} role="menu">
          <button type="button" role="menuitem" onClick={onHide}>
            {translation.notInterested}
          </button>
          {(
            [
              ["follow", translation.followUser],
              ["mute", translation.muteUser],
              ["block", translation.blockUser],
            ] as const
          ).map(([action, label]) => (
            <button
              key={action}
              type="button"
              role="menuitem"
              aria-busy={pendingUserActions.has(action)}
              disabled={completedUserActions.has(action)}
              onClick={() => userAction(action)}
            >
              {label}
              {completedUserActions.has(action) ? ` · ${translation.userActionCompleted}` : ""}
            </button>
          ))}
          <button
            type="button"
            role="menuitem"
            onClick={() => setListEditor((current) => !current)}
          >
            {translation.manageLists}
          </button>
          {listEditor && (
            <div className="list-membership-editor">
              <input
                aria-label={translation.listId}
                inputMode="numeric"
                pattern="[0-9]+"
                maxLength={30}
                value={listId}
                onChange={(event) => setListId(event.target.value)}
              />
              <button
                type="button"
                role="menuitem"
                aria-busy={listPendingCount > 0}
                onClick={() => listAction("add")}
              >
                {translation.addToList}
                {listOptimisticAction?.id === listId && listOptimisticAction.action === "add"
                  ? ` · ${translation.userActionCompleted}`
                  : ""}
              </button>
              <button
                type="button"
                role="menuitem"
                aria-busy={listPendingCount > 0}
                onClick={() => listAction("remove")}
              >
                {translation.removeFromList}
                {listOptimisticAction?.id === listId && listOptimisticAction.action === "remove"
                  ? ` · ${translation.userActionCompleted}`
                  : ""}
              </button>
            </div>
          )}
          {links.map(([label, href]) => (
            <a key={label} href={href} target="_blank" rel="noreferrer" role="menuitem">
              {label}
            </a>
          ))}
          {error && <p className="post-menu-error">{translation.userActionFailed}</p>}
        </div>
      )}
    </div>
  );
}
