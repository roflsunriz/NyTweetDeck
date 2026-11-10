import { expect, test } from "bun:test";
import { prefetchImages } from "./media-prefetch";

test("image warming limits candidates, duplicate URLs, request rate, and recent retries", () => {
  const originalNow = Date.now;
  let now = 100_000;
  Date.now = () => now;
  const loaded: string[] = [];
  const load = (url: string) => {
    loaded.push(url);
  };
  try {
    prefetchImages(["https://example.test/0", "https://example.test/0", "javascript:bad"], load);
    expect(loaded).toHaveLength(1);
    for (let i = 1; i < 20; i++) prefetchImages([`https://example.test/${i}`], load);
    expect(loaded).toHaveLength(8);
    now += 60_001;
    prefetchImages(["https://example.test/0"], load);
    expect(loaded).toHaveLength(8);
    now += 60_001;
    prefetchImages(["https://example.test/0"], load);
    expect(loaded).toHaveLength(9);
  } finally {
    Date.now = originalNow;
  }
});
