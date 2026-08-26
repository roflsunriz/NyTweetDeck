import { ArrowLeft, Bell, Clock3, Home, List, type LucideIcon, UserRound } from "lucide-react";
import { type FormEvent, useState } from "react";
import type { Translation } from "../i18n/translations";
import { columnKinds, type ColumnKind } from "../model/layout";
import { Modal } from "./modal";

interface AddColumnDialogProps {
  translation: Translation;
  onAdd: (kind: ColumnKind, target: string | null) => void;
  onClose: () => void;
}

const icons: Record<ColumnKind, LucideIcon> = {
  home: Home,
  notifications: Bell,
  history: Clock3,
  user: UserRound,
  list: List,
};

export function AddColumnDialog({ translation, onAdd, onClose }: AddColumnDialogProps) {
  const [pendingKind, setPendingKind] = useState<ColumnKind | null>(null);
  const [target, setTarget] = useState("");

  const chooseKind = (kind: ColumnKind) => {
    if (kind === "user" || kind === "list") {
      setPendingKind(kind);
      setTarget("");
      return;
    }
    onAdd(kind, null);
  };

  const submitTarget = (event: FormEvent) => {
    event.preventDefault();
    if (pendingKind !== null && target.trim().length > 0) {
      onAdd(pendingKind, target.trim());
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
            <span>{translation.columnTarget}</span>
            <input
              required
              maxLength={200}
              placeholder={
                pendingKind === "user" ? translation.userTargetHint : translation.listTargetHint
              }
              value={target}
              onChange={(event) => setTarget(event.target.value)}
            />
          </label>
          <button className="primary-button" type="submit">
            {translation.confirmAddColumn}
          </button>
        </form>
      )}
    </Modal>
  );
}
