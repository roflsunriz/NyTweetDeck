import {
  type TouchEvent as ReactTouchEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
  type WheelEvent as ReactWheelEvent,
} from "react";

const TOUCH_REFRESH_DISTANCE = 48;
const WHEEL_GESTURE_END_MILLISECONDS = 300;

interface ManualRefreshAtTopHandlers {
  onWheel: (event: ReactWheelEvent<HTMLElement>) => void;
  onTouchStart: (event: ReactTouchEvent<HTMLElement>) => void;
  onTouchMove: (event: ReactTouchEvent<HTMLElement>) => void;
  onTouchEnd: () => void;
  onTouchCancel: () => void;
}

export function useManualRefreshAtTop(refresh: () => Promise<unknown>): {
  manualRefreshing: boolean;
  manualRefreshHandlers: ManualRefreshAtTopHandlers;
} {
  const [manualRefreshing, setManualRefreshing] = useState(false);
  const refreshingRef = useRef(false);
  const wheelGestureLockedRef = useRef(false);
  const wheelGestureEndRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const touchStartYRef = useRef<number | null>(null);
  const touchRefreshReadyRef = useRef(false);

  const refreshManually = useCallback(async () => {
    if (refreshingRef.current) {
      return;
    }
    refreshingRef.current = true;
    setManualRefreshing(true);
    try {
      await refresh();
    } finally {
      refreshingRef.current = false;
      setManualRefreshing(false);
    }
  }, [refresh]);

  const finishWheelGestureLater = useCallback(() => {
    if (wheelGestureEndRef.current !== null) {
      clearTimeout(wheelGestureEndRef.current);
    }
    wheelGestureEndRef.current = setTimeout(() => {
      wheelGestureLockedRef.current = false;
      wheelGestureEndRef.current = null;
    }, WHEEL_GESTURE_END_MILLISECONDS);
  }, []);

  useEffect(
    () => () => {
      if (wheelGestureEndRef.current !== null) {
        clearTimeout(wheelGestureEndRef.current);
      }
    },
    [],
  );

  const onWheel = useCallback(
    (event: ReactWheelEvent<HTMLElement>) => {
      const isUpwardVerticalScroll =
        event.deltaY < 0 && Math.abs(event.deltaY) >= Math.abs(event.deltaX);
      if (event.currentTarget.scrollTop > 0 || !isUpwardVerticalScroll) {
        return;
      }
      finishWheelGestureLater();
      if (wheelGestureLockedRef.current) {
        return;
      }
      wheelGestureLockedRef.current = true;
      void refreshManually();
    },
    [finishWheelGestureLater, refreshManually],
  );

  const resetTouchGesture = useCallback(() => {
    touchStartYRef.current = null;
    touchRefreshReadyRef.current = false;
  }, []);

  const onTouchStart = useCallback((event: ReactTouchEvent<HTMLElement>) => {
    const firstTouch = event.touches[0] ?? null;
    touchStartYRef.current =
      event.currentTarget.scrollTop <= 0 && firstTouch !== null ? firstTouch.clientY : null;
    touchRefreshReadyRef.current = false;
  }, []);

  const onTouchMove = useCallback(
    (event: ReactTouchEvent<HTMLElement>) => {
      const firstTouch = event.touches[0] ?? null;
      const startY = touchStartYRef.current;
      if (startY === null || firstTouch === null || event.currentTarget.scrollTop > 0) {
        resetTouchGesture();
        return;
      }
      touchRefreshReadyRef.current = firstTouch.clientY - startY >= TOUCH_REFRESH_DISTANCE;
    },
    [resetTouchGesture],
  );

  const onTouchEnd = useCallback(() => {
    const shouldRefresh = touchRefreshReadyRef.current;
    resetTouchGesture();
    if (shouldRefresh) {
      void refreshManually();
    }
  }, [refreshManually, resetTouchGesture]);

  return {
    manualRefreshing,
    manualRefreshHandlers: {
      onWheel,
      onTouchStart,
      onTouchMove,
      onTouchEnd,
      onTouchCancel: resetTouchGesture,
    },
  };
}
