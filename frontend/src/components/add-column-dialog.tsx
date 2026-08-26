import {
  ArrowLeft,
  Bell,
  Clock3,
  Flame,
  Home,
  List,
  Mail,
  Search,
  type LucideIcon,
  UserRound,
} from "lucide-react";
import { type FormEvent, useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import { columnKinds, type ColumnKind } from "../model/layout";
import { Modal } from "./modal";

interface AddColumnDialogProps {
  translation: Translation;
  accountId: string | null;
  onAdd: (kind: ColumnKind, target: string | null, label?: string | null) => void;
  onClose: () => void;
  initialKind?: ColumnKind;
}

interface ListOption {
  id: string;
  name: string;
  description: string | null;
  ownerName: string | null;
  ownerUsername: string | null;
  memberCount: number;
  subscriberCount: number;
  source: "mine" | "suggested" | "search";
}

interface ListDirectoryPage {
  lists: ListOption[];
  nextCursor: string | null;
}

const icons: Record<ColumnKind, LucideIcon> = {
  home: Home,
  following: UserRound,
  search: Search,
  notifications: Bell,
  history: Clock3,
  user: UserRound,
  list: List,
  messages: Mail,
  trends: Flame,
};

export function AddColumnDialog({
  translation,
  accountId,
  onAdd,
  onClose,
  initialKind,
}: AddColumnDialogProps) {
  const [pendingKind, setPendingKind] = useState<ColumnKind | null>(initialKind ?? null);
  const [target, setTarget] = useState("");
  const [busy, setBusy] = useState(false);
  const [targetError, setTargetError] = useState<string | null>(null);
  const [listOptions, setListOptions] = useState<ListOption[]>([]);

  useEffect(() => {
    if (pendingKind !== "list" || accountId === null) {
      return;
    }
    const controller = new AbortController();
    setBusy(true);
    setTargetError(null);
    void Promise.allSettled([
      loadLists(accountId, "mine", undefined, controller.signal),
      loadLists(accountId, "suggested", undefined, controller.signal),
    ]).then((results) => {
      if (controller.signal.aborted) return;
      const lists = results.flatMap((result) =>
        result.status === "fulfilled" ? result.value.lists : [],
      );
      setListOptions(uniqueLists(lists));
      if (results.every((result) => result.status === "rejected")) {
        setTargetError(translation.listLoadError);
      }
      setBusy(false);
    });
    return () => controller.abort();
  }, [accountId, pendingKind, translation.listLoadError]);

  const chooseKind = (kind: ColumnKind) => {
    if (kind === "user" || kind === "list" || kind === "search") {
      setPendingKind(kind);
      setTarget("");
      setTargetError(null);
      return;
    }
    onAdd(kind, null);
  };

  const submitTarget = async (event: FormEvent) => {
    event.preventDefault();
    const normalized = target.trim();
    if (pendingKind === null || normalized.length === 0) {
      return;
    }
    if (pendingKind === "search") {
      onAdd("search", normalized, normalized);
      return;
    }
    if (accountId === null) {
      setTargetError(translation.loginRequiredDescription);
      return;
    }
    setBusy(true);
    setTargetError(null);
    try {
      if (pendingKind === "user") {
        const params = new URLSearchParams({ accountId, username: normalized });
        const response = await fetch(`/api/v1/users/resolve?${params}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const user = (await response.json()) as {
          id: string;
          username: string;
          displayName: string;
        };
        onAdd("user", user.id, `@${user.username}`);
      } else {
        const page = await loadLists(accountId, "search", normalized);
        setListOptions(uniqueLists(page.lists));
        if (page.lists.length === 0) {
          setTargetError(translation.noLists);
        }
      }
    } catch {
      setTargetError(
        pendingKind === "user" ? translation.userResolveError : translation.listLoadError,
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={translation.addColumn} closeLabel={translation.close} onClose={onClose}>
      {pendingKind === null ? (
        <>
          <p className="modal-description">{translation.addColumnDescription}</p>
          <div className="column-type-grid">
            {columnKinds.map((kind) => {
              const Icon = icons[kind];
              const text = translation.column[kind];
              return (
                <button
                  className="column-type-card"
                  data-column-kind={kind}
                  key={kind}
                  type="button"
                  onClick={() => chooseKind(kind)}
                >
                  <span className="column-type-icon">
                    <Icon aria-hidden="true" size={22} />
                  </span>
                  <span>
                    <strong>{text.title}</strong>
                    <small>{text.description}</small>
                  </span>
                </button>
              );
            })}
          </div>
        </>
      ) : (
        <form className="column-target-form" onSubmit={submitTarget}>
          <button className="back-button" type="button" onClick={() => setPendingKind(null)}>
            <ArrowLeft aria-hidden="true" size={17} />
            {translation.back}
          </button>
          <label>
            <span>
              {pendingKind === "list" ? translation.listSearch : translation.columnTarget}
            </span>
            <input
              required
              maxLength={200}
              placeholder={
                pendingKind === "user"
                  ? translation.userTargetHint
                  : pendingKind === "search"
                    ? translation.searchTargetHint
                    : translation.listSearchHint
              }
              value={target}
              onChange={(event) => setTarget(event.target.value)}
            />
          </label>
          <button className="primary-button" type="submit" disabled={busy}>
            {busy
              ? translation.loading
              : pendingKind === "list"
                ? translation.listSearch
                : translation.confirmAddColumn}
          </button>
          {targetError !== null && <p className="setup-error">{targetError}</p>}
          {pendingKind === "list" && (
            <div className="list-option-groups">
              <ListOptions
                title={translation.yourLists}
                options={listOptions.filter((option) => option.source === "mine")}
                onSelect={(option) => onAdd("list", option.id, option.name)}
              />
              <ListOptions
                title={translation.suggestedLists}
                options={listOptions.filter((option) => option.source !== "mine")}
                onSelect={(option) => onAdd("list", option.id, option.name)}
              />
              {!busy && listOptions.length === 0 && targetError === null && (
                <p className="modal-description">{translation.noLists}</p>
              )}
            </div>
          )}
        </form>
      )}
    </Modal>
  );
}

function ListOptions({
  title,
  options,
  onSelect,
}: {
  title: string;
  options: ListOption[];
  onSelect: (option: ListOption) => void;
}) {
  if (options.length === 0) return null;
  return (
    <section className="list-option-group">
      <h3>{title}</h3>
      {options.map((option) => (
        <button
          className="list-option"
          key={option.id}
          type="button"
          onClick={() => onSelect(option)}
        >
          <strong>{option.name}</strong>
          {option.ownerUsername !== null && <span>@{option.ownerUsername}</span>}
          {option.description !== null && <small>{option.description}</small>}
        </button>
      ))}
    </section>
  );
}

async function loadLists(
  accountId: string,
  scope: ListOption["source"],
  query?: string,
  signal?: AbortSignal,
): Promise<ListDirectoryPage> {
  const params = new URLSearchParams({ accountId, scope });
  if (query !== undefined) params.set("query", query);
  const response = await fetch(`/api/v1/lists?${params}`, { signal });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return (await response.json()) as ListDirectoryPage;
}

function uniqueLists(lists: ListOption[]): ListOption[] {
  const byId = new Map<string, ListOption>();
  for (const list of lists) {
    if (!byId.has(list.id)) byId.set(list.id, list);
  }
  return [...byId.values()];
}
