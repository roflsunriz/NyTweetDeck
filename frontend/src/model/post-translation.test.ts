import { afterEach, describe, expect, test } from "bun:test";
import {
  hasTranslatableText,
  loadPostTranslation,
  shouldTranslatePost,
  translationTargetsLocale,
} from "./post-translation";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe("post translation language matching", () => {
  test("skips text made only of mentions, media URLs, whitespace, numbers or symbols", () => {
    for (const text of [
      "",
      " \n\t",
      "@user",
      "@user_123 @second",
      "＠user",
      "@user https://t.co/photo",
      "HTTPS://t.co/photo",
      "@user\n＠second https://t.co/photo 🎉",
      "123 … ! 🎉",
      "(@user) @123456789012345",
    ]) {
      expect(hasTranslatableText(text)).toBe(false);
    }
  });

  test("preserves prose, hashtags, email addresses and non-ASCII letters", () => {
    for (const text of [
      "@user Hello! https://t.co/photo",
      "@user #Hello",
      "@user ありがとう",
      "@user مرحبا",
      "@user नमस्ते",
      "@user Привет",
      "@user 你好",
      "name@example.com",
      "username",
      "@abcdefghijklmnop",
      "https://t.co/photo　ありがとう",
      "https://t.co/photo\u00a0Hello",
      "𐐀",
    ]) {
      expect(hasTranslatableText(text)).toBe(true);
    }
  });

  test("translates only when a known post language differs from the UI language", () => {
    expect(shouldTranslatePost("en", "ja")).toBe(true);
    expect(shouldTranslatePost("pt-BR", "ja")).toBe(true);
    expect(shouldTranslatePost("ja", "ja")).toBe(false);
    expect(shouldTranslatePost("zh-CN", "zh")).toBe(false);
    expect(shouldTranslatePost("und", "ja")).toBe(false);
    expect(shouldTranslatePost(null, "ja")).toBe(false);
  });

  test("rejects a translation response that is not provided by X", async () => {
    globalThis.fetch = (async () =>
      Response.json({
        postId: "123",
        sourceLanguage: "en",
        targetLanguage: "ja",
        text: "翻訳",
        provider: "Google",
      })) as unknown as typeof fetch;

    await expect(
      loadPostTranslation({
        accountId: "account-provider-check",
        postId: "123",
        sourceLanguage: "en",
        targetLanguage: "ja",
      }),
    ).rejects.toThrow("Invalid X translation response");
  });

  test("uses a pretranslation only for the active UI base language", () => {
    expect(translationTargetsLocale("ja-JP", "ja")).toBe(true);
    expect(translationTargetsLocale("en", "ja")).toBe(false);
  });

  test("automatically retries a rate-limited X translation after Retry-After", async () => {
    let calls = 0;
    const retryDelays: number[] = [];
    globalThis.fetch = (async () => {
      calls += 1;
      if (calls === 1) {
        return new Response(null, { status: 429, headers: { "Retry-After": "0" } });
      }
      return Response.json({
        postId: "rate-limited-post",
        sourceLanguage: "en",
        targetLanguage: "ja",
        text: "再試行後の翻訳",
        provider: "X",
      });
    }) as unknown as typeof fetch;

    const result = await loadPostTranslation({
      accountId: "account-rate-limit",
      postId: "rate-limited-post",
      sourceLanguage: "en",
      targetLanguage: "ja",
      onRetryScheduled: (seconds) => retryDelays.push(seconds),
    });

    expect(result.text).toBe("再試行後の翻訳");
    expect(calls).toBe(2);
    expect(retryDelays).toEqual([1, 0]);
  });

  test("limits fresh X translations to two concurrent requests", async () => {
    let active = 0;
    let maximumActive = 0;
    let calls = 0;
    const releases: Array<() => void> = [];
    globalThis.fetch = ((input: RequestInfo | URL) => {
      calls += 1;
      active += 1;
      maximumActive = Math.max(maximumActive, active);
      const postId = /\/posts\/([^/]+)\/translation/.exec(String(input))?.[1] ?? "unknown";
      return new Promise<Response>((resolve) => {
        releases.push(() => {
          active -= 1;
          resolve(
            Response.json({
              postId,
              sourceLanguage: "en",
              targetLanguage: "ja",
              text: `translation-${postId}`,
              provider: "X",
            }),
          );
        });
      });
    }) as unknown as typeof fetch;

    const pending = ["concurrency-a", "concurrency-b", "concurrency-c"].map((postId) =>
      loadPostTranslation({
        accountId: "account-concurrency",
        postId,
        sourceLanguage: "en",
        targetLanguage: "ja",
      }),
    );

    await waitUntil(() => releases.length === 2);
    expect(calls).toBe(2);
    expect(maximumActive).toBe(2);
    releases.shift()?.();
    await waitUntil(() => calls === 3);
    expect(maximumActive).toBe(2);
    for (const release of releases.splice(0)) release();
    await Promise.all(pending);
  });
});

async function waitUntil(condition: () => boolean): Promise<void> {
  const deadline = Date.now() + 1_000;
  while (!condition()) {
    if (Date.now() >= deadline) throw new Error("条件待機がタイムアウトしました。");
    await Bun.sleep(1);
  }
}
