import { afterEach, describe, expect, test } from "bun:test";
import {
  formatDetailedShare,
  formatTranslatedDetailedShare,
  postShareUrl,
  resolveTranslatedShareBody,
} from "./post-share";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
});

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

  test("翻訳版はGrokプリ翻訳をそのまま使い引用にも適用する", () => {
    const text = formatDetailedShare(
      {
        ...basePost(),
        text: "Original body",
        preTranslated: {
          text: "翻訳された本文",
          sourceLanguage: "en",
          targetLanguage: "ja",
          provider: "Grok",
        },
        quotedPost: {
          id: "122",
          text: "Quoted original",
          language: "en",
          createdAt: null,
          author: {
            id: "24",
            username: "quoted",
            displayName: "Quoted Author",
            avatarUrl: null,
            verified: false,
          },
          preTranslated: {
            text: "翻訳された引用",
            sourceLanguage: "en",
            targetLanguage: "ja",
            provider: "Grok",
          },
          media: [],
        },
      },
      "ja",
      new Date("2026-09-06T15:00:00.000Z").getTime(),
      { translated: true },
    );
    expect(text).toContain("翻訳された本文");
    expect(text).not.toContain("Original body");
    expect(text).toContain("> 翻訳された引用");
    expect(text).not.toContain("Quoted original");
    expect(text.endsWith("https://x.com/alice/status/123")).toBe(true);
  });

  test("プリ翻訳がない場合は翻訳版でも原文へ戻す", () => {
    const text = formatDetailedShare(basePost(), "ja", Date.now(), { translated: true });
    expect(text).toContain("本文テスト");
  });
});

describe("resolveTranslatedShareBody", () => {
  function liveScope(postId: string) {
    return {
      accountId: "account-1",
      postId,
      text: "Hello world",
      language: "en",
    };
  }

  function mockLiveTranslation(text: string, calls: string[]) {
    globalThis.fetch = (async (input: Parameters<typeof fetch>[0]) => {
      calls.push(String(input));
      return Response.json({
        postId: /\/posts\/([^/]+)\/translation/.exec(String(input))?.[1] ?? "unknown",
        sourceLanguage: "en",
        targetLanguage: "ja",
        text,
        provider: "X",
      });
    }) as unknown as typeof fetch;
  }

  test("プリ翻訳を通信なしでそのまま使う", async () => {
    const calls: string[] = [];
    mockLiveTranslation("使われない", calls);
    const body = await resolveTranslatedShareBody(
      {
        ...liveScope("pre-1"),
        preTranslated: {
          text: "プリ翻訳の本文",
          sourceLanguage: "en",
          targetLanguage: "ja",
          provider: "Grok",
        },
      },
      "ja",
    );
    expect(body).toBe("プリ翻訳の本文");
    expect(calls).toHaveLength(0);
  });

  test("プールにない場合はライブ翻訳を取得して再利用する", async () => {
    const calls: string[] = [];
    mockLiveTranslation("ライブ翻訳の本文", calls);
    const first = await resolveTranslatedShareBody(liveScope("live-1"), "ja");
    const second = await resolveTranslatedShareBody(liveScope("live-1"), "ja");
    expect(first).toBe("ライブ翻訳の本文");
    expect(second).toBe("ライブ翻訳の本文");
    expect(calls).toHaveLength(1);
  });

  test("取得に失敗した場合は原文へ戻す", async () => {
    globalThis.fetch = (async () => new Response(null, { status: 400 })) as unknown as typeof fetch;
    const body = await resolveTranslatedShareBody(liveScope("live-fail-1"), "ja");
    expect(body).toBe("Hello world");
  });

  test("応答が遅い場合は打ち切って原文で返す", async () => {
    globalThis.fetch = (async () => {
      await new Promise<void>((resolve) => globalThis.setTimeout(resolve, 500));
      return Response.json({
        postId: "live-timeout-1",
        sourceLanguage: "en",
        targetLanguage: "ja",
        text: "遅れて届く訳文",
        provider: "X",
      });
    }) as unknown as typeof fetch;
    const body = await resolveTranslatedShareBody(liveScope("live-timeout-1"), "ja", {
      timeoutMilliseconds: 20,
    });
    expect(body).toBe("Hello world");
  });

  test("同じ言語や翻訳不要な本文では通信しない", async () => {
    const calls: string[] = [];
    mockLiveTranslation("使われない", calls);
    expect(await resolveTranslatedShareBody({ ...liveScope("same-1"), language: "ja" }, "ja")).toBe(
      "Hello world",
    );
    expect(
      await resolveTranslatedShareBody(
        { ...liveScope("url-1"), text: "https://example.test/x", language: "en" },
        "ja",
      ),
    ).toBe("https://example.test/x");
    expect(calls).toHaveLength(0);
  });

  test("翻訳版の全文は解決済み本文で組み立てる", async () => {
    const calls: string[] = [];
    mockLiveTranslation("ライブ翻訳の本文", calls);
    const text = await formatTranslatedDetailedShare(
      "account-1",
      { ...basePost(), id: "live-full-1", text: "Hello world", language: "en" },
      "ja",
      "ja",
    );
    expect(text).toContain("ライブ翻訳の本文");
    expect(text).not.toContain("Hello world");
    expect(text.endsWith("https://x.com/alice/status/live-full-1")).toBe(true);
  });
});
