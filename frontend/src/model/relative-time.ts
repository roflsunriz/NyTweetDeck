import { useSyncExternalStore } from "react";

const listeners = new Set<() => void>();
let timer: ReturnType<typeof setTimeout> | undefined;

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  scheduleTick();
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0 && timer !== undefined) {
      clearTimeout(timer);
      timer = undefined;
    }
  };
}

function scheduleTick(): void {
  if (timer !== undefined || listeners.size === 0) {
    return;
  }
  const delay = 60_000 - (Date.now() % 60_000) + 10;
  timer = setTimeout(() => {
    timer = undefined;
    for (const listener of listeners) {
      listener();
    }
    scheduleTick();
  }, delay);
}

function minuteSnapshot(): number {
  return Math.floor(Date.now() / 60_000);
}

export function useRelativeTime(value: string | null, locale: string): string | null {
  const minute = useSyncExternalStore(subscribe, minuteSnapshot, minuteSnapshot);
  return formatRelativeTime(value, locale, minute * 60_000);
}

export function formatRelativeTime(
  value: string | null,
  locale: string,
  nowMilliseconds = Date.now(),
): string | null {
  if (value === null) {
    return null;
  }
  const milliseconds = new Date(value).getTime() - nowMilliseconds;
  if (!Number.isFinite(milliseconds)) {
    return null;
  }
  const formatter = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  const minutes = Math.round(milliseconds / 60_000);
  if (Math.abs(minutes) < 60) {
    return formatter.format(minutes, "minute");
  }
  const hours = Math.round(minutes / 60);
  if (Math.abs(hours) < 24) {
    return formatter.format(hours, "hour");
  }
  return formatter.format(Math.round(hours / 24), "day");
}
