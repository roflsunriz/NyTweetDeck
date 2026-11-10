import { expect, test } from "bun:test";
import { PageSnapshots } from "./page-snapshots";

test("snapshots isolate keys, expire, and evict the oldest of 32 entries", () => {
  const cache = new PageSnapshots();
  const originalNow = Date.now;
  let now = 0;
  Date.now = () => now;
  try {
    cache.write("account-a:ja", { text: "a" });
    expect(cache.read("account-b:ja")).toBeNull();
    expect(cache.read("account-a:en")).toBeNull();
    expect(cache.read("account-a:ja")).toEqual({ text: "a" });
    now = 300_001;
    expect(cache.read("account-a:ja")).toBeNull();
    for (let i = 0; i < 33; i++) cache.write(String(i), i);
    expect(cache.read("0")).toBeNull();
    expect(cache.read("32")).toBe(32);
  } finally {
    Date.now = originalNow;
  }
});
