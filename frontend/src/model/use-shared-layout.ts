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

const automaticLoadRetryLimit = 8;
const automaticLoadRetryBaseDelayMs = 500;
const automaticLoadRetryMaximumDelayMs = 5_000;
const automaticConflictRetryLimit = 4;

type LayoutMutation = (layout: AppLayout) => AppLayout;

function applyMutations(layout: AppLayout, mutations: readonly LayoutMutation[]): AppLayout {
  return mutations.reduce((current, mutation) => mutation(current), layout);
}

export function useSharedLayout(storage: StorageLike = window.localStorage): {
  layout: AppLayout | null;
  error: SharedLayoutError | null;
  setLayout: (action: SetStateAction<AppLayout>) => void;
  retry: () => void;
} {
  const [layout, setLayoutState] = useState<AppLayout | null>(null);
  const [error, setError] = useState<SharedLayoutError | null>(null);
  const [loadRetryToken, setLoadRetryToken] = useState(0);
  const [saveFlushToken, setSaveFlushToken] = useState(0);
  const confirmedSnapshotRef = useRef<SharedLayoutSnapshot | null>(null);
  const pendingMutationsRef = useRef<LayoutMutation[]>([]);
  const saveQueueRef = useRef<Promise<void>>(Promise.resolve());
  const loadFailureCountRef = useRef(0);

  const rebaseOnSnapshot = useCallback((snapshot: SharedLayoutSnapshot) => {
    confirmedSnapshotRef.current = snapshot;
    setLayoutState(applyMutations(snapshot.layout, pendingMutationsRef.current));
  }, []);

  useEffect(() => {
    void loadRetryToken;
    let active = true;
    let retryTimer: number | null = null;
    void initializeSharedLayout(storage)
      .then((snapshot) => {
        if (active) {
          loadFailureCountRef.current = 0;
          rebaseOnSnapshot(snapshot);
          setError(null);
        }
      })
      .catch(() => {
        if (!active) return;
        setError("load");
        const failureCount = loadFailureCountRef.current;
        if (failureCount < automaticLoadRetryLimit) {
          loadFailureCountRef.current = failureCount + 1;
          const delay = Math.min(
            automaticLoadRetryBaseDelayMs * 2 ** failureCount,
            automaticLoadRetryMaximumDelayMs,
          );
          retryTimer = window.setTimeout(() => {
            setLoadRetryToken((current) => current + 1);
          }, delay);
        }
      });
    return () => {
      active = false;
      if (retryTimer !== null) window.clearTimeout(retryTimer);
    };
  }, [loadRetryToken, rebaseOnSnapshot, storage]);

  const flushPendingMutations = useCallback((): Promise<void> => {
    saveQueueRef.current = saveQueueRef.current
      .catch(() => undefined)
      .then(async () => {
        let conflictCount = 0;
        while (pendingMutationsRef.current.length > 0) {
          const confirmed = confirmedSnapshotRef.current;
          if (confirmed === null) return;
          const captured = [...pendingMutationsRef.current];
          const desired = applyMutations(confirmed.layout, captured);
          if (JSON.stringify(desired) === JSON.stringify(confirmed.layout)) {
            pendingMutationsRef.current.splice(0, captured.length);
            setLayoutState(applyMutations(confirmed.layout, pendingMutationsRef.current));
            continue;
          }
          try {
            const snapshot = await saveSharedLayout(desired, confirmed.revision);
            confirmedSnapshotRef.current = snapshot;
            pendingMutationsRef.current.splice(0, captured.length);
            setLayoutState(applyMutations(snapshot.layout, pendingMutationsRef.current));
            conflictCount = 0;
            setError(null);
          } catch (saveError) {
            if (saveError instanceof SharedLayoutConflictError) {
              confirmedSnapshotRef.current = saveError.snapshot;
              if (JSON.stringify(saveError.snapshot.layout) === JSON.stringify(desired)) {
                pendingMutationsRef.current.splice(0, captured.length);
                setLayoutState(
                  applyMutations(saveError.snapshot.layout, pendingMutationsRef.current),
                );
                conflictCount = 0;
                setError(null);
                continue;
              }
              setLayoutState(
                applyMutations(saveError.snapshot.layout, pendingMutationsRef.current),
              );
              conflictCount += 1;
              if (conflictCount >= automaticConflictRetryLimit) {
                setError("conflict");
                return;
              }
              continue;
            }
            setError("save");
            return;
          }
        }
        if (pendingMutationsRef.current.length === 0) {
          setError(null);
        }
      });
    return saveQueueRef.current;
  }, []);

  useEffect(() => {
    void saveFlushToken;
    void flushPendingMutations();
  }, [flushPendingMutations, saveFlushToken]);

  const initialized = layout !== null;
  useEffect(() => {
    if (!initialized || typeof EventSource === "undefined") return;
    const source = new EventSource("/api/v1/settings/events");
    const refresh = () => {
      void flushPendingMutations().finally(async () => {
        if (pendingMutationsRef.current.length !== 0) return;
        try {
          const snapshot = await loadSharedLayout();
          if (snapshot.revision > (confirmedSnapshotRef.current?.revision ?? 0)) {
            rebaseOnSnapshot(snapshot);
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
          Number(value.revision) > (confirmedSnapshotRef.current?.revision ?? 0)
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
  }, [flushPendingMutations, initialized, rebaseOnSnapshot]);

  const setLayout = useCallback((action: SetStateAction<AppLayout>) => {
    if (confirmedSnapshotRef.current === null) return;
    const mutation: LayoutMutation = typeof action === "function" ? action : () => action;
    pendingMutationsRef.current.push(mutation);
    setLayoutState((current) => {
      if (current === null) return current;
      return mutation(current);
    });
    setSaveFlushToken((current) => current + 1);
  }, []);

  const retry = useCallback(() => {
    if (error === "conflict") {
      setSaveFlushToken((current) => current + 1);
    } else if (layout === null) {
      loadFailureCountRef.current = 0;
      setLoadRetryToken((current) => current + 1);
    } else {
      setSaveFlushToken((current) => current + 1);
    }
  }, [error, layout]);
  return { layout, error, setLayout, retry };
}
