import { describe, expect, test } from "bun:test";
import {
  createDefaultLayout,
  layoutStorageKey,
  loadLayout,
  moveItem,
  rememberTrendSearch,
  saveLayout,
  type StorageLike,
} from "./layout";

class MemoryStorage implements StorageLike {
  private readonly entries = new Map<string, string>();

  getItem(key: string): string | null {
    return this.entries.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.entries.set(key, value);
  }

  removeItem(key: string): void {
    this.entries.delete(key);
  }
}

describe("layout storage", () => {
  test("uses an empty Japanese system layout by default", () => {
    const layout = loadLayout(new MemoryStorage());

    expect(layout).toEqual(createDefaultLayout());
    expect(layout.columns).toHaveLength(0);
    expect(layout.display.autoTranslatePosts).toBe(true);
  });

  test("round-trips a valid layout", () => {
    const storage = new MemoryStorage();
    const layout = {
      ...createDefaultLayout(),
      locale: "en" as const,
      columns: [{ id: "home-1", kind: "home" as const, target: null, label: null }],
    };

    saveLayout(storage, layout);

    expect(loadLayout(storage)).toEqual(layout);
  });

  test("removes corrupted or unsupported data", () => {
    const storage = new MemoryStorage();
    storage.setItem(layoutStorageKey, '{"version":999,"columns":[]}');

    expect(loadLayout(storage)).toEqual(createDefaultLayout());
    expect(storage.getItem(layoutStorageKey)).toBeNull();
  });

  test("force-migrates version 1 layout to the current version", () => {
    const storage = new MemoryStorage();
    storage.setItem(
      layoutStorageKey,
      JSON.stringify({
        version: 1,
        columns: [{ id: "legacy-home", kind: "home" }],
        navItems: [...createDefaultLayout().navItems],
        locale: "ja",
        theme: "dark",
      }),
    );

    const migrated = loadLayout(storage);

    expect(migrated.version).toBe(10);
    expect(migrated.columns[0]?.target).toBeNull();
    expect(migrated.activeAccountId).toBeNull();
    expect(migrated.display.mediaPreview).toBe(true);
    expect(migrated.display.videoLoop).toBe(true);
    expect(migrated.display.videoVolume).toBe(100);
    expect(JSON.parse(String(storage.getItem(layoutStorageKey))).version).toBe(10);
  });

  test("migrates version 2 layout while preserving columns and account", () => {
    const storage = new MemoryStorage();
    storage.setItem(
      layoutStorageKey,
      JSON.stringify({
        version: 2,
        columns: [{ id: "home", kind: "home", target: null }],
        navItems: [...createDefaultLayout().navItems],
        locale: "ja",
        theme: "dark",
        activeAccountId: "account-1",
      }),
    );

    const migrated = loadLayout(storage);

    expect(migrated.version).toBe(10);
    expect(migrated.columns).toHaveLength(1);
    expect(migrated.activeAccountId).toBe("account-1");
    expect(migrated.display.accentColor).toBe("blue");
  });

  test("migrates version 3 columns with an empty display label", () => {
    const storage = new MemoryStorage();
    storage.setItem(
      layoutStorageKey,
      JSON.stringify({
        ...createDefaultLayout(),
        version: 3,
        columns: [{ id: "list", kind: "list", target: "42" }],
      }),
    );

    const migrated = loadLayout(storage);

    expect(migrated.version).toBe(10);
    expect(migrated.columns[0]?.label).toBeNull();
    expect(migrated.display.autoTranslatePosts).toBe(true);
  });

  test("migrates version 4 with automatic post translation enabled by default", () => {
    const storage = new MemoryStorage();
    const {
      autoTranslatePosts: _removed,
      videoLoop: _removedLoop,
      videoVolume: _removedVolume,
      ...legacyDisplay
    } = createDefaultLayout().display;
    storage.setItem(
      layoutStorageKey,
      JSON.stringify({
        ...createDefaultLayout(),
        version: 4,
        display: legacyDisplay,
      }),
    );

    const migrated = loadLayout(storage);

    expect(migrated.version).toBe(10);
    expect(migrated.display.autoTranslatePosts).toBe(true);
    expect(migrated.display.videoLoop).toBe(true);
    expect(migrated.display.videoVolume).toBe(100);
  });

  test("migrates version 5 while preserving columns and adds trend search history", () => {
    const storage = new MemoryStorage();
    const current = createDefaultLayout();
    const { trendSearchHistory: _removed, ...legacy } = current;
    const {
      videoLoop: _removedLoop,
      videoVolume: _removedVolume,
      ...legacyDisplay
    } = current.display;
    storage.setItem(
      layoutStorageKey,
      JSON.stringify({
        ...legacy,
        version: 5,
        display: legacyDisplay,
        columns: [{ id: "trends", kind: "trends", target: "AI", label: null }],
      }),
    );

    const migrated = loadLayout(storage);

    expect(migrated.version).toBe(10);
    expect(migrated.columns[0]?.target).toBe("AI");
    expect(migrated.trendSearchHistory).toEqual([]);
    expect(migrated.display.videoLoop).toBe(true);
    expect(migrated.display.videoVolume).toBe(100);
  });

  test("migrates version 6 while preserving trend history and display settings", () => {
    const storage = new MemoryStorage();
    const current = createDefaultLayout();
    const {
      videoLoop: _removedLoop,
      videoVolume: _removedVolume,
      ...legacyDisplay
    } = current.display;
    storage.setItem(
      layoutStorageKey,
      JSON.stringify({
        ...current,
        version: 6,
        display: { ...legacyDisplay, videoAutoplay: true },
        trendSearchHistory: ["AI"],
      }),
    );

    const migrated = loadLayout(storage);

    expect(migrated.version).toBe(10);
    expect(migrated.trendSearchHistory).toEqual(["AI"]);
    expect(migrated.display.videoAutoplay).toBe(true);
    expect(migrated.display.videoLoop).toBe(true);
    expect(migrated.display.videoVolume).toBe(100);
  });

  test("migrates version 7 with relevance as the remembered reply order", () => {
    const storage = new MemoryStorage();
    const { replySort: _removed, ...legacy } = createDefaultLayout();
    storage.setItem(layoutStorageKey, JSON.stringify({ ...legacy, version: 7 }));

    const migrated = loadLayout(storage);

    expect(migrated.version).toBe(10);
    expect(migrated.replySort).toBe("relevance");
    expect(JSON.parse(String(storage.getItem(layoutStorageKey))).replySort).toBe("relevance");
  });

  test("migrates version 8 to independent translation, refresh, quality, and column order settings", () => {
    const storage = new MemoryStorage();
    const current = createDefaultLayout();
    const { translationLocale: _removedTranslationLocale, ...legacyLayout } = current;
    const {
      autoRefreshTimelines: _removedRefresh,
      videoQuality: _removedQuality,
      ...legacyDisplay
    } = current.display;
    storage.setItem(
      layoutStorageKey,
      JSON.stringify({
        ...legacyLayout,
        version: 8,
        display: legacyDisplay,
        columns: [{ id: "home", kind: "home", target: null, label: null }],
      }),
    );

    const migrated = loadLayout(storage);

    expect(migrated.version).toBe(10);
    expect(migrated.translationLocale).toBe(migrated.locale);
    expect(migrated.columns[0]?.sort).toBe("latest");
    expect(migrated.display.autoRefreshTimelines).toBe(true);
    expect(migrated.display.videoQuality).toBe("auto");
  });

  test("remembers unique recent trend searches with a bounded history", () => {
    let history: string[] = [];
    for (let index = 0; index < 25; index += 1) {
      history = rememberTrendSearch(history, `query-${index}`);
    }

    expect(history).toHaveLength(20);
    expect(history[0]).toBe("query-24");
    expect(rememberTrendSearch(history, " QUERY-24 ")).toEqual(history);
    expect(rememberTrendSearch(history, "   ")).toEqual(history);
  });

  test("moves an item without mutating the source", () => {
    const source = ["a", "b", "c"];

    expect(moveItem(source, 0, 2)).toEqual(["b", "c", "a"]);
    expect(source).toEqual(["a", "b", "c"]);
    expect(moveItem(source, -1, 2)).toEqual(source);
  });
});
