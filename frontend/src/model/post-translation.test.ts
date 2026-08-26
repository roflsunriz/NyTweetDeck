import { afterEach, describe, expect, test } from "bun:test";
import { shouldTranslatePost } from "./post-translation";

afterEach(() => {
  // Translation requests are exercised by the PostCard interaction tests.
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
});
