import { useCallback, useEffect, useRef, useState } from "react";

interface OptimisticToggle {
  actionKey: string;
  active: boolean;
  count: number;
  setActive: (value: boolean) => void;
  setCount: (value: number) => void;
  enableAction: string;
  disableAction: string;
}

interface ToggleQueue extends OptimisticToggle {
  confirmedActive: boolean;
  confirmedCount: number;
  desiredActive: boolean;
  running: boolean;
}

export function useOptimisticToggle(
  request: (action: string) => Promise<void>,
  onError: (error: unknown) => void,
) {
  const [pendingActions, setPendingActions] = useState<ReadonlySet<string>>(() => new Set());
  const queuesRef = useRef(new Map<string, ToggleQueue>());
  const requestRef = useRef(request);
  const onErrorRef = useRef(onError);
  const mountedRef = useRef(true);
  requestRef.current = request;
  onErrorRef.current = onError;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const publishPending = useCallback(() => {
    if (mountedRef.current) setPendingActions(new Set(queuesRef.current.keys()));
  }, []);

  const publishState = useCallback((queue: ToggleQueue, active: boolean) => {
    if (!mountedRef.current) return;
    queue.setActive(active);
    queue.setCount(countForState(queue, active));
  }, []);

  const drain = useCallback(
    async (queue: ToggleQueue) => {
      if (queue.running) return;
      queue.running = true;
      while (queue.desiredActive !== queue.confirmedActive) {
        const requestedActive = queue.desiredActive;
        try {
          await requestRef.current(requestedActive ? queue.enableAction : queue.disableAction);
          queue.confirmedCount = countForState(queue, requestedActive);
          queue.confirmedActive = requestedActive;
        } catch (error) {
          if (queue.desiredActive === requestedActive) {
            queue.desiredActive = queue.confirmedActive;
            publishState(queue, queue.confirmedActive);
            onErrorRef.current(error);
            break;
          }
        }
      }
      queue.running = false;
      if (queue.desiredActive === queue.confirmedActive) {
        queuesRef.current.delete(queue.actionKey);
      }
      publishPending();
    },
    [publishPending, publishState],
  );

  const toggle = useCallback(
    (input: OptimisticToggle) => {
      let queue = queuesRef.current.get(input.actionKey);
      if (queue === undefined) {
        queue = {
          ...input,
          confirmedActive: input.active,
          confirmedCount: input.count,
          desiredActive: input.active,
          running: false,
        };
        queuesRef.current.set(input.actionKey, queue);
      } else {
        queue.setActive = input.setActive;
        queue.setCount = input.setCount;
        queue.enableAction = input.enableAction;
        queue.disableAction = input.disableAction;
      }
      queue.desiredActive = !queue.desiredActive;
      publishState(queue, queue.desiredActive);
      publishPending();
      void drain(queue);
    },
    [drain, publishPending, publishState],
  );

  const reconcile = useCallback(
    (actionKey: string, active: boolean, count: number) => {
      const queue = queuesRef.current.get(actionKey);
      if (queue === undefined) return false;
      queue.confirmedActive = active;
      queue.confirmedCount = count;
      publishState(queue, queue.desiredActive);
      return true;
    },
    [publishState],
  );

  return { pendingActions, reconcile, toggle };
}

function countForState(queue: ToggleQueue, active: boolean): number {
  return Math.max(0, queue.confirmedCount + Number(active) - Number(queue.confirmedActive));
}
