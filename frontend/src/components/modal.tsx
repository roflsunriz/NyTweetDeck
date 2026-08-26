import { X } from "lucide-react";
import { type ReactNode, useEffect, useId } from "react";

interface ModalProps {
  title: string;
  closeLabel: string;
  onClose: () => void;
  children: ReactNode;
}

export function Modal({ title, closeLabel, onClose, children }: ModalProps) {
  const titleId = useId();

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return (
    <div className="modal-backdrop">
      <section aria-labelledby={titleId} aria-modal="true" className="modal-panel" role="dialog">
        <header className="modal-header">
          <h2 id={titleId}>{title}</h2>
          <button className="icon-button" type="button" aria-label={closeLabel} onClick={onClose}>
            <X aria-hidden="true" size={20} />
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}
