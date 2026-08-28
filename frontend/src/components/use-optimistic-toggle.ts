import { useRef, useState } from "react";

interface OptimisticToggle {
  actionKey: string;
  active: boolean;
  count: number;
  setActive: (value: boolean) => void;
  setCount: (value: number) => void;
  enableAction: string;
  disableAction: string;
}

export function useOptimisticToggle(
  request: (action: string) => Promise<void>,
  onError: (error: unknown) => void,
) {
  const [pendingActions, setPendingActions] = useState<ReadonlySet<string>>(() => new Set());
  const pendingActionsRef = useRef(new Set<string>());

  const toggle = ({
    actionKey,
    active,
    count,
    setActive,
    setCount,
    enableAction,
    disableAction,
  }: OptimisticToggle) => {
    if (pendingActionsRef.current.has(actionKey)) return;
    const nextActive = !active;
    pendingActionsRef.current.add(actionKey);
    setPendingActions(new Set(pendingActionsRef.current));
    setActive(nextActive);
    setCount(Math.max(0, count + (nextActive ? 1 : -1)));

    void (async () => {
      try {
        await request(nextActive ? enableAction : disableAction);
      } catch (error) {
        setActive(active);
        setCount(count);
        onError(error);
      } finally {
        pendingActionsRef.current.delete(actionKey);
        setPendingActions(new Set(pendingActionsRef.current));
      }
    })();
  };

  return { pendingActions, toggle };
}
