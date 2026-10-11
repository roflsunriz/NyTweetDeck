import { useCallback, useEffect, useRef } from "react";

let overlaySequence = 0;

export function useOverlayRoute(route: string, onClose: () => void): () => void {
  const tokenRef = useRef(`nytd-overlay-${++overlaySequence}`);
  const activeRef = useRef(false);
  const onCloseRef = useRef(onClose);
  const previousStateRef = useRef<unknown>(null);
  const previousUrlRef = useRef("");
  const closeTimerRef = useRef<number | null>(null);
  const closeListenerRef = useRef<(() => void) | null>(null);
  const reusableEntryRef = useRef(false);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    const token = tokenRef.current;
    if (!reusableEntryRef.current) {
      previousUrlRef.current = window.location.href;
      previousStateRef.current = window.history.state;
    }
    activeRef.current = true;
    const state = isRecord(window.history.state) ? window.history.state : {};
    const nextUrl = new URL(window.location.href);
    nextUrl.hash = `/${route}`;
    const ancestors = Array.isArray(state.nytdOverlayAncestors) ? state.nytdOverlayAncestors : [];
    const nextState = {
      ...state,
      nytdOverlayToken: token,
      nytdOverlayAncestors: [...ancestors, state.nytdOverlayToken].filter(
        (value) => typeof value === "string",
      ),
    };
    // StrictMode replays setup after cleanup. Reuse the entry that cleanup restored,
    // rather than leaving another copy of the parent in the Back stack.
    if (reusableEntryRef.current) window.history.replaceState(nextState, "", nextUrl.href);
    else window.history.pushState(nextState, "", nextUrl.href);
    reusableEntryRef.current = false;
    const handlePopState = () => {
      if (!activeRef.current) return;
      if (isRecord(window.history.state) && window.history.state.nytdOverlayToken === token) {
        return;
      }
      if (
        isRecord(window.history.state) &&
        Array.isArray(window.history.state.nytdOverlayAncestors) &&
        window.history.state.nytdOverlayAncestors.includes(token)
      )
        return;
      activeRef.current = false;
      onCloseRef.current();
    };
    window.addEventListener("popstate", handlePopState);
    return () => {
      window.removeEventListener("popstate", handlePopState);
      if (closeTimerRef.current !== null) clearTimeout(closeTimerRef.current);
      if (closeListenerRef.current !== null)
        window.removeEventListener("popstate", closeListenerRef.current);
      if (
        activeRef.current &&
        isRecord(window.history.state) &&
        window.history.state.nytdOverlayToken === token
      ) {
        window.history.replaceState(state, "", previousUrlRef.current);
        reusableEntryRef.current = true;
      }
      activeRef.current = false;
    };
  }, [route]);

  return useCallback(() => {
    if (!activeRef.current) return;
    if (
      isRecord(window.history.state) &&
      window.history.state.nytdOverlayToken !== tokenRef.current
    )
      return;
    activeRef.current = false;
    const token = tokenRef.current;
    if (isRecord(window.history.state) && window.history.state.nytdOverlayToken === token) {
      const finishClose = () => {
        if (closeTimerRef.current !== null) clearTimeout(closeTimerRef.current);
        if (closeListenerRef.current !== null)
          window.removeEventListener("popstate", closeListenerRef.current);
        closeTimerRef.current = null;
        closeListenerRef.current = null;
        onCloseRef.current();
      };
      closeListenerRef.current = finishClose;
      window.addEventListener("popstate", finishClose, { once: true });
      closeTimerRef.current = window.setTimeout(() => {
        if (isRecord(window.history.state) && window.history.state.nytdOverlayToken === token) {
          window.history.replaceState(previousStateRef.current, "", previousUrlRef.current);
        }
        finishClose();
      }, 100);
      // Keep the current UI until Back has restored its parent's history entry.
      window.history.back();
    } else {
      onCloseRef.current();
    }
  }, []);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
