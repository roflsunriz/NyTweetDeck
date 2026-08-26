import { describe, expect, test } from "bun:test";
import {
  createDefaultLayout,
  layoutStorageKey,
  loadLayout,
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
  });

  test("round-trips a valid layout", () => {
    const storage = new MemoryStorage();
    const layout = {
      ...createDefaultLayout(),
      locale: "en" as const,
      columns: [{ id: "home-1", kind: "home" as const, target: null }],
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

  test("force-migrates version 1 layout to version 2", () => {
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

    expect(migrated.version).toBe(2);
    expect(migrated.columns[0]?.target).toBeNull();
    expect(migrated.activeAccountId).toBeNull();
    expect(JSON.parse(String(storage.getItem(layoutStorageKey))).version).toBe(2);
  });
});
