import { useCallback, useEffect, useRef, useState, type SetStateAction } from "react";
import type { AppLayout, StorageLike } from "./layout";
import {
  initializeSharedLayout,
  loadSharedLayout,
  saveSharedLayout,
  SharedLayoutConflictError,
  type SharedLayoutSnapshot,
} from "./shared-layout";

export type SharedLayoutError = "load" | "save" | "conflict";

export function useSharedLayout(storage: StorageLike = window.localStorage): {
  layout: AppLayout | null;
  error: SharedLayoutError | null;
  setLayout: (action: SetStateAction<AppLayout>) => void;
  retry: () => void;
} {
  const [layout, setLayoutState] = useState<AppLayout | null>(null);
  const [error, setError] = useState<SharedLayoutError | null>(null);
  const [loadRetryToken, setLoadRetryToken] = useState(0);
  const [saveRetryToken, setSaveRetryToken] = useState(0);
  const revisionRef = useRef(0);
  const lastAppliedRef = useRef<string | null>(null);
  const pendingWritesRef = useRef(0);
  const saveQueueRef = useRef<Promise<void>>(Promise.resolve());

  const applySnapshot = useCallback((snapshot: SharedLayoutSnapshot) => {
    revisionRef.current = snapshot.revision;
    lastAppliedRef.current = JSON.stringify(snapshot.layout);
    setLayoutState(snapshot.layout);
  }, []);

  useEffect(() => {
    void loadRetryToken;
    let active = true;
    void initializeSharedLayout(storage)
      .then((snapshot) => {
        if (active) {
          applySnapshot(snapshot);
          setError(null);
        }
      })
      .catch(() => {
        if (active) setError("load");
      });
    return () => {
      active = false;
    };
  }, [applySnapshot, loadRetryToken, storage]);

  useEffect(() => {
    void saveRetryToken;
    if (layout === null) return;
    const serialized = JSON.stringify(layout);
    if (serialized === lastAppliedRef.current) return;
    pendingWritesRef.current += 1;
    saveQueueRef.current = saveQueueRef.current
      .catch(() => undefined)
      .then(async () => {
        try {
          const snapshot = await saveSharedLayout(layout, revisionRef.current);
          revisionRef.current = snapshot.revision;
          lastAppliedRef.current = JSON.stringify(snapshot.layout);
          setError(null);
        } catch (saveError) {
          if (saveError instanceof SharedLayoutConflictError) {
            applySnapshot(saveError.snapshot);
            setError("conflict");
          } else {
            setError("save");
          }
        } finally {
          pendingWritesRef.current -= 1;
        }
      });
  }, [applySnapshot, layout, saveRetryToken]);

  const initialized = layout !== null;
  useEffect(() => {
    if (!initialized || typeof EventSource === "undefined") return;
    const source = new EventSource("/api/v1/settings/events");
    const refresh = () => {
      void saveQueueRef.current.finally(async () => {
        if (pendingWritesRef.current !== 0) return;
        try {
          const snapshot = await loadSharedLayout();
          if (snapshot.revision > revisionRef.current) {
            applySnapshot(snapshot);
            setError(null);
          }
        } catch {
          // EventSource reconnects automatically and focus/visibility will retry this refresh.
        }
      });
    };
    const handleUpdate = (event: MessageEvent<string>) => {
      try {
        const value: unknown = JSON.parse(event.data);
        if (
          typeof value === "object" &&
          value !== null &&
          "revision" in value &&
          Number(value.revision) > revisionRef.current
        ) {
          refresh();
        }
      } catch {
        // Invalid event data is ignored; focus and visibility refreshes remain available.
      }
    };
    source.addEventListener("layout-settings-update", handleUpdate);
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      source.removeEventListener("layout-settings-update", handleUpdate);
      source.close();
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [applySnapshot, initialized]);

  const setLayout = useCallback((action: SetStateAction<AppLayout>) => {
    setLayoutState((current) => {
      if (current === null) return current;
      return typeof action === "function" ? action(current) : action;
    });
  }, []);

  const retry = useCallback(() => {
    if (error === "conflict") {
      setError(null);
    } else if (layout === null) {
      setLoadRetryToken((current) => current + 1);
    } else {
      setSaveRetryToken((current) => current + 1);
    }
  }, [error, layout]);
  return { layout, error, setLayout, retry };
}
