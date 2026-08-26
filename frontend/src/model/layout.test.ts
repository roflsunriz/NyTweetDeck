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
      columns: [{ id: "home-1", kind: "home" as const }],
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
});
