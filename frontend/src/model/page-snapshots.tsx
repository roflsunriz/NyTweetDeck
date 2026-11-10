import { createContext, type ReactNode, useContext, useRef } from "react";

/** In-memory only. Account/locale are part of every caller's key. */
export class PageSnapshots {
  private entries = new Map<string, { value: unknown; savedAt: number }>();
  read<T>(key: string): T | null {
    const entry = this.entries.get(key);
    if (entry === undefined) return null;
    if (Date.now() - entry.savedAt > 5 * 60_000) {
      this.entries.delete(key);
      return null;
    }
    return entry.value as T;
  }
  clear(): void {
    this.entries.clear();
  }
  write<T>(key: string, value: T): void {
    this.entries.delete(key);
    this.entries.set(key, { value, savedAt: Date.now() });
    while (this.entries.size > 32) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) break;
      this.entries.delete(oldest);
    }
  }
}

const SnapshotContext = createContext<PageSnapshots | null>(null);
export function PageSnapshotProvider({ children }: { children: ReactNode }) {
  const cache = useRef(new PageSnapshots());
  return <SnapshotContext.Provider value={cache.current}>{children}</SnapshotContext.Provider>;
}
export function usePageSnapshots(): PageSnapshots {
  const shared = useContext(SnapshotContext);
  const fallback = useRef(new PageSnapshots());
  return shared ?? fallback.current;
}
