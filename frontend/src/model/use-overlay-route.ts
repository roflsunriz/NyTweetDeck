import { useCallback, useEffect, useRef } from "react";

let overlaySequence = 0;

export function useOverlayRoute(route: string, onClose: () => void): () => void {
  const tokenRef = useRef(`nytd-overlay-${++overlaySequence}`);
  const activeRef = useRef(false);
  const onCloseRef = useRef(onClose);
  const previousStateRef = useRef<unknown>(null);
  const previousUrlRef = useRef("");
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    const token = tokenRef.current;
    previousUrlRef.current = window.location.href;
    previousStateRef.current = window.history.state;
    activeRef.current = true;
    const state = isRecord(window.history.state) ? window.history.state : {};
    const nextUrl = new URL(window.location.href);
    nextUrl.hash = `/${route}`;
    window.history.pushState({ ...state, nytdOverlayToken: token }, "", nextUrl.href);
    const handlePopState = () => {
      if (!activeRef.current) return;
      if (isRecord(window.history.state) && window.history.state.nytdOverlayToken === token) {
        return;
      }
      activeRef.current = false;
      onCloseRef.current();
    };
    window.addEventListener("popstate", handlePopState);
    return () => {
      window.removeEventListener("popstate", handlePopState);
      if (
        activeRef.current &&
        isRecord(window.history.state) &&
        window.history.state.nytdOverlayToken === token
      ) {
        window.history.replaceState(state, "", previousUrlRef.current);
      }
      activeRef.current = false;
    };
  }, [route]);

  return useCallback(() => {
    if (!activeRef.current) return;
    activeRef.current = false;
    const token = tokenRef.current;
    if (isRecord(window.history.state) && window.history.state.nytdOverlayToken === token) {
      window.history.back();
      window.setTimeout(() => {
        if (isRecord(window.history.state) && window.history.state.nytdOverlayToken === token) {
          window.history.replaceState(previousStateRef.current, "", previousUrlRef.current);
        }
      }, 100);
    }
    onCloseRef.current();
  }, []);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
