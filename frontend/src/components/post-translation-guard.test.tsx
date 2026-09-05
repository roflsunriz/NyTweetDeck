import { afterEach, expect, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
import { translate } from "../i18n/translations";
import { PostCard, type TimelinePost } from "./post-card";
import { PostTranslationProvider } from "./post-translation-context";

const originalFetch = globalThis.fetch;
const originalObserver = globalThis.IntersectionObserver;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
  globalThis.IntersectionObserver = originalObserver;
});

test("does not request or show failure for non-prose posts, replies and quotes while translating prose", async () => {
  globalThis.IntersectionObserver = undefined as unknown as typeof IntersectionObserver;
  const requests: string[] = [];
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const id = /\/posts\/([^/]+)\/translation/.exec(String(input))?.[1];
    if (id === undefined) return Response.json({});
    requests.push(id);
    return id === "guard-prose"
      ? Response.json({
          postId: id,
          sourceLanguage: "en",
          targetLanguage: "ja",
          text: "通常本文の翻訳",
          provider: "X",
        })
      : new Response(null, { status: 400 });
  }) as typeof fetch;
  const posts = ["", " \n", "@user", "@user https://t.co/photo", "https://t.co/photo"].map(
    (text, index) => post(`guard-${index}`, text),
  );
  const quoteContainer = post("guard-quote-container", "@user");
  quoteContainer.quotedPost = post("guard-quote", "@user https://t.co/photo");
  render(
    <PostTranslationProvider
      value={{ locale: "ja", autoTranslatePosts: true, setAutoTranslatePosts: () => undefined }}
    >
      {[...posts, quoteContainer, post("guard-prose", "@user Hello #world https://t.co/photo")].map(
        (item) => (
          <PostCard
            key={item.id}
            post={item}
            accountId="guard-account"
            translation={translate("ja")}
          />
        ),
      )}
    </PostTranslationProvider>,
  );
  expect(await screen.findByText("通常本文の翻訳")).toBeDefined();
  expect(requests).toEqual(["guard-prose"]);
  expect(screen.queryByText(translate("ja").translationFailed)).toBeNull();
  expect(screen.queryByText(translate("ja").translationLoading)).toBeNull();
  expect(document.querySelectorAll(".post-media img").length).toBeGreaterThan(0);
});

function post(id: string, text: string): TimelinePost {
  return {
    id,
    text,
    language: "en",
    createdAt: null,
    author: {
      id: "guard-author",
      username: "guard",
      displayName: "Guard",
      avatarUrl: null,
      verified: false,
    },
    repostedBy: null,
    replyCount: 0,
    repostCount: 0,
    quoteCount: 0,
    likeCount: 0,
    bookmarkCount: 0,
    viewCount: 0,
    liked: false,
    reposted: false,
    bookmarked: false,
    replyToPostId: "guard-parent",
    replyToUsername: "user",
    quotedPost: null,
    media: [
      {
        id: `media-${id}`,
        type: "photo",
        url: "https://example.com/guard.png",
        previewUrl: "https://example.com/guard.png",
      },
    ],
  };
}
