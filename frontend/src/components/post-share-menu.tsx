import { Share2 } from "lucide-react";
import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

interface ShareMenuProps {
  label: string;
  urlLabel: string;
  detailsLabel: string;
  translatedDetailsLabel: string;
  onCopyUrl: () => void;
  onCopyDetails: () => void;
  onCopyTranslatedDetails: () => void;
  extraActions: Array<{ id: string; label: string; run: () => Promise<void> }>;
}

/** A body portal escapes column clipping; both placement and scrolling use the
 * visible viewport so every action remains reachable on short/mobile screens. */
export function ShareMenu({
  label,
  urlLabel,
  detailsLabel,
  translatedDetailsLabel,
  onCopyUrl,
  onCopyDetails,
  onCopyTranslatedDetails,
  extraActions,
}: ShareMenuProps) {
  const menuId = useId();
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const actions = [
    { id: "share-url", label: urlLabel, run: onCopyUrl },
    { id: "share-details", label: detailsLabel, run: onCopyDetails },
    { id: "share-details-translated", label: translatedDetailsLabel, run: onCopyTranslatedDetails },
    ...extraActions,
  ];

  useLayoutEffect(() => {
    if (!open) return;
    const position = () => {
      const trigger = triggerRef.current;
      const menu = menuRef.current;
      if (trigger === null || menu === null) return;
      const viewport = window.visualViewport;
      const left = (viewport?.offsetLeft ?? 0) + 8;
      const top = (viewport?.offsetTop ?? 0) + 8;
      const right = left + (viewport?.width ?? innerWidth) - 16;
      const bottom = top + (viewport?.height ?? innerHeight) - 16;
      const anchor = trigger.getBoundingClientRect();
      const scrollTop = menu.scrollTop;
      menu.style.maxWidth = `${Math.max(1, right - left)}px`;
      menu.style.maxHeight = `${Math.max(1, bottom - top)}px`;
      const bounds = menu.getBoundingClientRect();
      const above = Math.max(0, anchor.top - top - 4);
      const below = Math.max(0, bottom - anchor.bottom - 4);
      const upwards = bounds.height <= above || above > below;
      const height = Math.min(bounds.height, upwards ? above : below);
      menu.style.maxHeight = `${Math.max(1, height)}px`;
      const preferredLeft =
        getComputedStyle(trigger).direction === "rtl" ? anchor.left : anchor.right - bounds.width;
      menu.style.left = `${Math.max(left, Math.min(right - bounds.width, preferredLeft))}px`;
      menu.style.top = `${Math.max(top, Math.min(bottom - height, upwards ? anchor.top - height - 4 : anchor.bottom + 4))}px`;
      menu.scrollTop = scrollTop;
    };
    const repositionAfterScroll = (event: Event) => {
      if (event.target instanceof Node && menuRef.current?.contains(event.target)) return;
      position();
    };
    position();
    menuRef.current?.querySelector<HTMLButtonElement>("button")?.focus();
    window.addEventListener("resize", position);
    window.addEventListener("scroll", repositionAfterScroll, true);
    window.visualViewport?.addEventListener("resize", position);
    window.visualViewport?.addEventListener("scroll", position);
    return () => {
      window.removeEventListener("resize", position);
      window.removeEventListener("scroll", repositionAfterScroll, true);
      window.visualViewport?.removeEventListener("resize", position);
      window.visualViewport?.removeEventListener("scroll", position);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      const target = event.target;
      if (
        target instanceof Node &&
        !menuRef.current?.contains(target) &&
        !triggerRef.current?.contains(target)
      )
        setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      setOpen(false);
      triggerRef.current?.focus();
    };
    document.addEventListener("pointerdown", outside, true);
    document.addEventListener("keydown", closeOnEscape, true);
    return () => {
      document.removeEventListener("pointerdown", outside, true);
      document.removeEventListener("keydown", closeOnEscape, true);
    };
  }, [open]);

  return (
    <div className="share-menu">
      <button
        ref={triggerRef}
        type="button"
        className="post-action"
        data-post-action="share"
        aria-controls={menuId}
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={label}
        onClick={() => setOpen((current) => !current)}
      >
        <Share2 aria-hidden="true" size={16} />
      </button>
      {open &&
        createPortal(
          <div
            ref={menuRef}
            id={menuId}
            role="menu"
            className="post-share-menu"
            aria-label={label}
            onKeyDown={(event) => {
              if (event.key === "Tab") {
                setOpen(false);
                return;
              }
              if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
              event.preventDefault();
              const items = [...event.currentTarget.querySelectorAll<HTMLButtonElement>("button")];
              const current = items.findIndex((item) => item === document.activeElement);
              const next =
                event.key === "Home"
                  ? 0
                  : event.key === "End"
                    ? items.length - 1
                    : (current + (event.key === "ArrowDown" ? 1 : -1) + items.length) %
                      items.length;
              items[next]?.focus();
            }}
          >
            {actions.map((action) => (
              <button
                key={action.id}
                type="button"
                role="menuitem"
                data-post-action={action.id}
                onClick={() => {
                  setOpen(false);
                  triggerRef.current?.focus();
                  action.run();
                }}
              >
                {action.label}
              </button>
            ))}
          </div>,
          document.body,
        )}
    </div>
  );
}
