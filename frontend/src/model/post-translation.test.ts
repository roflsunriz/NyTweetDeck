import { afterEach, describe, expect, test } from "bun:test";
import {
  loadPostTranslation,
  shouldTranslatePost,
  translationTargetsLocale,
} from "./post-translation";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

describe("post translation language matching", () => {
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
});
