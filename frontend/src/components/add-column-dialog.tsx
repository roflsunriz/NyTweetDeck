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
import { type FormEvent, useState } from "react";
import type { Translation } from "../i18n/translations";
import { columnKinds, type ColumnKind } from "../model/layout";
import { loadListDirectory, type ListOption, uniqueLists } from "../model/list-directory";
import type { ListCandidatesState } from "../model/use-list-candidates";
import { Modal } from "./modal";

interface AddColumnDialogProps {
  translation: Translation;
  accountId: string | null;
  listCandidates: ListCandidatesState;
  onAdd: (kind: ColumnKind, target: string | null, label?: string | null) => void;
  onClose: () => void;
  initialKind?: ColumnKind;
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
  listCandidates,
  onAdd,
  onClose,
  initialKind,
}: AddColumnDialogProps) {
  const [pendingKind, setPendingKind] = useState<ColumnKind | null>(initialKind ?? null);
  const [target, setTarget] = useState("");
  const [busy, setBusy] = useState(false);
  const [targetError, setTargetError] = useState<string | null>(null);
  const [listSearchResults, setListSearchResults] = useState<ListOption[] | null>(null);
  const listOptions = listSearchResults ?? listCandidates.options;

  const chooseKind = (kind: ColumnKind) => {
    if (kind === "user" || kind === "list" || kind === "search") {
      setPendingKind(kind);
      setTarget("");
      setTargetError(null);
      setListSearchResults(null);
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
        const page = await loadListDirectory(accountId, "search", normalized);
        setListSearchResults(uniqueLists(page.lists));
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
              onChange={(event) => {
                setTarget(event.target.value);
                setTargetError(null);
                if (pendingKind === "list") setListSearchResults(null);
              }}
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
          {pendingKind === "list" && listSearchResults === null && listCandidates.error && (
            <p className="setup-error">{translation.listLoadError}</p>
          )}
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
              {!busy &&
                listOptions.length === 0 &&
                targetError === null &&
                (listSearchResults !== null || listCandidates.ready) && (
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
