import { afterEach, describe, expect, test } from "bun:test";
import { fetchWithTimeout } from "./fetch-with-timeout";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe("bounded local API fetch", () => {
  test("aborts a stalled browser request at the configured deadline", async () => {
    globalThis.fetch = ((_input: Parameters<typeof fetch>[0], init?: Parameters<typeof fetch>[1]) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener(
          "abort",
          () => reject(init.signal?.reason ?? new DOMException("Aborted", "AbortError")),
          { once: true },
        );
      })) as unknown as typeof fetch;

    await expect(fetchWithTimeout("/api/test", {}, 5)).rejects.toThrow("Request timed out");
  });
});
