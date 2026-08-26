import { describe, expect, test } from "bun:test";
import {
  createDefaultLayout,
  layoutStorageKey,
  loadLayout,
  moveItem,
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

    expect(migrated.version).toBe(5);
    expect(migrated.columns[0]?.target).toBeNull();
    expect(migrated.activeAccountId).toBeNull();
    expect(migrated.display.mediaPreview).toBe(true);
    expect(JSON.parse(String(storage.getItem(layoutStorageKey))).version).toBe(5);
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

    expect(migrated.version).toBe(5);
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

    expect(migrated.version).toBe(5);
    expect(migrated.columns[0]?.label).toBeNull();
    expect(migrated.display.autoTranslatePosts).toBe(true);
  });

  test("migrates version 4 with automatic post translation enabled by default", () => {
    const storage = new MemoryStorage();
    const { autoTranslatePosts: _removed, ...legacyDisplay } = createDefaultLayout().display;
    storage.setItem(
      layoutStorageKey,
      JSON.stringify({
        ...createDefaultLayout(),
        version: 4,
        display: legacyDisplay,
      }),
    );

    const migrated = loadLayout(storage);

    expect(migrated.version).toBe(5);
    expect(migrated.display.autoTranslatePosts).toBe(true);
  });

  test("moves an item without mutating the source", () => {
    const source = ["a", "b", "c"];

    expect(moveItem(source, 0, 2)).toEqual(["b", "c", "a"]);
    expect(source).toEqual(["a", "b", "c"]);
    expect(moveItem(source, -1, 2)).toEqual(source);
  });
});
