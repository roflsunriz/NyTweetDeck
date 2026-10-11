import { X } from "lucide-react";
import { type ReactNode, useEffect, useId, useRef } from "react";

interface ModalProps {
  title: string;
  closeLabel: string;
  onClose: () => void;
  children: ReactNode;
  presentation?: "modal" | "full-page" | "route";
}

export function Modal({
  title,
  closeLabel,
  onClose,
  children,
  presentation = "modal",
}: ModalProps) {
  const titleId = useId();
  const backdropRef = useRef<HTMLDivElement>(null);
  const presentationProps =
    presentation !== "modal"
      ? ({ role: "region" } as const)
      : ({ "aria-modal": "true", role: "dialog" } as const);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      const backdrops = document.querySelectorAll(".modal-backdrop");
      if (event.key === "Escape" && backdrops.item(backdrops.length - 1) === backdropRef.current) {
        onClose();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return (
    <div
      ref={backdropRef}
      className={`modal-backdrop${presentation === "modal" ? "" : ` ${presentation}`}`}
    >
      <section
        aria-labelledby={titleId}
        className="modal-panel"
        data-media-scroll-root
        data-presentation={presentation}
        {...presentationProps}
      >
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
