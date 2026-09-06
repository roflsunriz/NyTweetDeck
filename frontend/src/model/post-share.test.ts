import { describe, expect, test } from "bun:test";
import { formatDetailedShare, postShareUrl } from "./post-share";

function basePost() {
  return {
    id: "123",
    text: "本文テスト",
    createdAt: "2026-09-06T12:00:00.000Z",
    author: {
      id: "42",
      username: "alice",
      displayName: "Alice",
      avatarUrl: null,
      verified: false,
    },
    media: [],
    quotedPost: null,
  };
}

describe("formatDetailedShare", () => {
  test("引用なしの基本形式を作る", () => {
    const text = formatDetailedShare(
      {
        ...basePost(),
        media: [{ url: "https://pbs.twimg.com/media/a.jpg" }],
      },
      "ja",
      new Date("2026-09-06T15:00:00.000Z").getTime(),
    );
    const lines = text.split("\n");
    expect(lines[0]).toBe("Alice@alice");
    expect(lines[1]).toBe("本文テスト");
    expect(lines).toContain("https://pbs.twimg.com/media/a.jpg");
    expect(lines[lines.length - 1]).toBe("https://x.com/alice/status/123");
    expect(text).toContain("時間前");
  });

  test("引用ありでは引用元を大なり記号で引用し元ポストURLだけを末尾にする", () => {
    const text = formatDetailedShare(
      {
        ...basePost(),
        quotedPost: {
          id: "122",
          text: "引用本文\n2行目",
          language: "ja",
          createdAt: "2026-09-05T12:00:00.000Z",
          author: {
            id: "24",
            username: "quoted",
            displayName: "Quoted Author",
            avatarUrl: null,
            verified: false,
          },
          media: [
            { id: "q1", type: "photo", url: "https://pbs.twimg.com/quote.jpg", previewUrl: "" },
          ],
        },
      },
      "ja",
      new Date("2026-09-06T15:00:00.000Z").getTime(),
    );
    expect(text).toContain("Quoted Author@quoted");
    expect(text).toContain("> 引用本文");
    expect(text).toContain("> 2行目");
    expect(text).not.toContain("https://pbs.twimg.com/quote.jpg");
    expect(text.endsWith("https://x.com/alice/status/123")).toBe(true);
  });

  test("postShareUrlはユーザー名形式のURLを作る", () => {
    expect(postShareUrl(basePost())).toBe("https://x.com/alice/status/123");
  });
});
