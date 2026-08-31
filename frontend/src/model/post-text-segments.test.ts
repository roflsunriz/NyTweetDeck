import { describe, expect, test } from "bun:test";

import { postTextSegments } from "./post-text-segments";

describe("ポスト本文の意味セグメント", () => {
  test("HTTP URLとハッシュタグを順序どおり抽出する", () => {
    expect(postTextSegments("参照 https://example.test/a?q=1 #NyTD")).toEqual([
      { kind: "text", text: "参照 " },
      { kind: "url", text: "https://example.test/a?q=1", url: "https://example.test/a?q=1" },
      { kind: "text", text: " " },
      { kind: "hashtag", text: "#NyTD" },
    ]);
  });

  test("末尾句読点と不釣り合いな閉じ括弧をリンク外へ残す", () => {
    expect(postTextSegments("(https://example.test/a(b))、次")).toEqual([
      { kind: "text", text: "(" },
      { kind: "url", text: "https://example.test/a(b)", url: "https://example.test/a(b)" },
      { kind: "text", text: ")、次" },
    ]);
  });

  test("HTTP(S)以外と壊れたURLをリンクにしない", () => {
    expect(postTextSegments("javascript:alert(1) https://")).toEqual([
      { kind: "text", text: "javascript:alert(1) https://" },
    ]);
  });
});
