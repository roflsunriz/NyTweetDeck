import { Check } from "lucide-react";
import type { Translation } from "../i18n/translations";
import { availableNavItemIds, type NavItemId } from "../model/layout";
import { Modal } from "./modal";

interface MenuEditorDialogProps {
  translation: Translation;
  selected: readonly NavItemId[];
  onChange: (items: NavItemId[]) => void;
  onClose: () => void;
}

export function MenuEditorDialog({
  translation,
  selected,
  onChange,
  onClose,
}: MenuEditorDialogProps) {
  const toggle = (item: NavItemId) => {
    if (selected.includes(item)) {
      onChange(selected.filter((current) => current !== item));
    } else {
      onChange([...selected, item]);
    }
  };

  return (
    <Modal title={translation.editMenu} closeLabel={translation.close} onClose={onClose}>
      <p className="modal-description">{translation.editMenuDescription}</p>
      <div className="menu-editor-grid">
        {availableNavItemIds.map((item) => {
          const checked = selected.includes(item);
          return (
            <button
              type="button"
              key={item}
              className={checked ? "selected" : ""}
              aria-pressed={checked}
              onClick={() => toggle(item)}
            >
              <span>{translation.nav[item]}</span>
              {checked && <Check aria-hidden="true" size={17} />}
            </button>
          );
        })}
      </div>
    </Modal>
  );
}
