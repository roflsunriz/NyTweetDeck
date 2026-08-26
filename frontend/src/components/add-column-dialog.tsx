import { Bell, Clock3, Home, List, type LucideIcon, UserRound } from "lucide-react";
import type { Translation } from "../i18n/translations";
import { columnKinds, type ColumnKind } from "../model/layout";
import { Modal } from "./modal";

interface AddColumnDialogProps {
  translation: Translation;
  onAdd: (kind: ColumnKind) => void;
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
  return (
    <Modal title={translation.addColumn} closeLabel={translation.close} onClose={onClose}>
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
              onClick={() => onAdd(kind)}
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
    </Modal>
  );
}
