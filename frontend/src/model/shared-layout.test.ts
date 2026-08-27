import { describe, expect, test } from "bun:test";
import { createDefaultLayout, layoutStorageKey, type StorageLike } from "./layout";
import {
  initializeSharedLayout,
  loadSharedLayout,
  saveSharedLayout,
  SharedLayoutConflictError,
  type SharedLayoutSnapshot,
} from "./shared-layout";

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

describe("shared layout persistence", () => {
  test("migrates one valid origin-local layout only when the shared store is empty", async () => {
    const storage = new MemoryStorage();
    const legacy = { ...createDefaultLayout(), theme: "dark" as const };
    storage.setItem(layoutStorageKey, JSON.stringify(legacy));
    let savedRequest: unknown;
    const fetcher = async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method !== "PUT") return new Response(null, { status: 204 });
      savedRequest = JSON.parse(String(init.body)) as unknown;
      return Response.json({ revision: 1, layout: legacy });
    };

    const snapshot = await initializeSharedLayout(storage, fetcher);

    expect(snapshot).toEqual({ revision: 1, layout: legacy });
    expect(savedRequest).toEqual({ expectedRevision: 0, layout: legacy });
    expect(storage.getItem(layoutStorageKey)).toBeNull();
  });

  test("always prefers the address-independent server layout over a divergent local origin", async () => {
    const storage = new MemoryStorage();
    storage.setItem(
      layoutStorageKey,
      JSON.stringify({ ...createDefaultLayout(), locale: "en", theme: "light" }),
    );
    const shared = { ...createDefaultLayout(), locale: "ja" as const, theme: "dark" as const };
    let putRequests = 0;
    const fetcher = async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "PUT") putRequests += 1;
      return Response.json({ revision: 8, layout: shared });
    };

    const snapshot = await initializeSharedLayout(storage, fetcher);

    expect(snapshot).toEqual({ revision: 8, layout: shared });
    expect(putRequests).toBe(0);
    expect(storage.getItem(layoutStorageKey)).toBeNull();
  });

  test("adopts the first initialized origin when two addresses migrate concurrently", async () => {
    const storage = new MemoryStorage();
    const local = { ...createDefaultLayout(), theme: "light" as const };
    const winner = { ...createDefaultLayout(), theme: "dark" as const };
    storage.setItem(layoutStorageKey, JSON.stringify(local));
    const fetcher = async (_input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method !== "PUT") return new Response(null, { status: 204 });
      return Response.json({ revision: 1, layout: winner }, { status: 409 });
    };

    const snapshot = await initializeSharedLayout(storage, fetcher);

    expect(snapshot.layout).toEqual(winner);
    expect(storage.getItem(layoutStorageKey)).toBeNull();
  });

  test("rejects stale writes with the current shared snapshot", async () => {
    const current: SharedLayoutSnapshot = {
      revision: 3,
      layout: { ...createDefaultLayout(), theme: "dark" },
    };
    const fetcher = async () => Response.json(current, { status: 409 });

    const result = saveSharedLayout(createDefaultLayout(), 2, fetcher);

    await expect(result).rejects.toBeInstanceOf(SharedLayoutConflictError);
    await result.catch((error: unknown) => {
      expect((error as SharedLayoutConflictError).snapshot).toEqual(current);
    });
  });

  test("rejects malformed shared responses instead of creating address-specific defaults", async () => {
    const fetcher = async () => Response.json({ revision: 1, layout: { version: 999 } });

    await expect(loadSharedLayout(fetcher)).rejects.toThrow("invalid");
  });

  test("keeps the legacy origin settings available when first-time migration fails", async () => {
    const storage = new MemoryStorage();
    const legacy = { ...createDefaultLayout(), theme: "dark" as const };
    storage.setItem(layoutStorageKey, JSON.stringify(legacy));
    const fetcher = async (_input: RequestInfo | URL, init?: RequestInit) =>
      init?.method === "PUT"
        ? new Response(null, { status: 500 })
        : new Response(null, { status: 204 });

    await expect(initializeSharedLayout(storage, fetcher)).rejects.toThrow("HTTP 500");
    expect(storage.getItem(layoutStorageKey)).toBe(JSON.stringify(legacy));
  });
});
