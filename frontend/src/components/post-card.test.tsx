import { afterEach, beforeEach, describe, expect, mock, test } from "bun:test";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { translate } from "../i18n/translations";
import { defaultDisplayPreferences } from "../model/layout";
import { PostCard, type TimelinePost } from "./post-card";
import { PostTranslationProvider } from "./post-translation-context";

const originalFetch = globalThis.fetch;
const originalIntersectionObserver = globalThis.IntersectionObserver;

beforeEach(() => {
  globalThis.IntersectionObserver = undefined as unknown as typeof IntersectionObserver;
});

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
  globalThis.IntersectionObserver = originalIntersectionObserver;
});

describe("post actions", () => {
  test("updates like count only after successful web mutation", async () => {
    const urls: string[] = [];
    globalThis.fetch = (async (input) => {
      urls.push(String(input));
      return Response.json({ postId: "100", action: "like" });
    }) as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    const likeButton = screen.getByRole("button", { name: "いいね" });
    expect(likeButton.textContent).toBe("3");
    await user.click(likeButton);
    await waitFor(() => expect(likeButton.textContent).toBe("4"));
    expect(likeButton.classList.contains("like-active")).toBe(true);
    expect(likeButton.querySelector("svg")?.getAttribute("fill")).toBe("currentColor");
    await user.click(likeButton);
    await waitFor(() => expect(likeButton.textContent).toBe("3"));
    expect(likeButton.classList.contains("like-active")).toBe(false);
    expect(likeButton.querySelector("svg")?.getAttribute("fill")).toBe("none");

    expect(urls[0]).toContain("/posts/100/actions/like?accountId=account-1");
    expect(urls[1]).toContain("/posts/100/actions/unlike?accountId=account-1");
  });

  test("colors engagement controls from the initially loaded X state", () => {
    render(
      <PostCard
        post={{ ...post(), liked: true, reposted: true }}
        accountId="account-1"
        translation={translate("ja")}
      />,
    );

    const likeButton = screen.getByRole("button", { name: "いいね" });
    const repostButton = screen.getByLabelText("リポスト");
    expect(likeButton.classList.contains("like-active")).toBe(true);
    expect(likeButton.querySelector("svg")?.getAttribute("fill")).toBe("currentColor");
    expect(repostButton.classList.contains("repost-active")).toBe(true);
  });

  test("renders a Community Note returned with the related post", () => {
    render(
      <PostCard
        post={{
          ...post(),
          communityNote: {
            title: "コミュニティノート",
            text: "この画像は2024年に撮影されたものです。",
            footer: "役に立ったと評価されました",
          },
        }}
        accountId="account-1"
        translation={translate("ja")}
      />,
    );

    const note = screen.getByTestId("community-note-card");
    expect(note.textContent).toContain("この画像は2024年に撮影されたものです。");
    expect(note.textContent).toContain("役に立ったと評価されました");
  });

  test("keeps state when mutation fails", async () => {
    globalThis.fetch = (async () =>
      Response.json(
        { detail: "X Web署名情報を取得できませんでした。" },
        { status: 502 },
      )) as unknown as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    const bookmarkButton = screen.getByRole("button", { name: "履歴に保存" });
    await user.click(bookmarkButton);

    await screen.findByText("ポスト操作に失敗しました。 X Web署名情報を取得できませんでした。");
    expect(bookmarkButton.textContent).toBe("4");
  });

  test("opens reply composer and the complete overflow menu", async () => {
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    await user.click(screen.getByRole("button", { name: "返信" }));
    expect(screen.getByRole("heading", { name: "返信" })).toBeDefined();
    await user.click(screen.getByRole("button", { name: "閉じる" }));
    await user.click(screen.getByLabelText("ポストメニュー"));

    expect(screen.getByRole("button", { name: "このポストに興味がない" })).toBeDefined();
    expect(screen.getByRole("link", { name: "コミュニティノートリクエスト" })).toBeDefined();
  });

  test("shows the at-username and hashtags and offers repost and quote choices", async () => {
    const user = userEvent.setup();
    const taggedPost = { ...post(), text: "hello #NyTweetDeck" };
    render(<PostCard post={taggedPost} accountId="account-1" translation={translate("ja")} />);

    expect(screen.getByText("@alice")).toBeDefined();
    expect(screen.queryByText("42")).toBeNull();
    expect(screen.getByText("#NyTweetDeck").classList.contains("hashtag")).toBe(true);
    await user.click(screen.getByLabelText("リポスト"));
    expect(screen.getByRole("button", { name: "リポスト" })).toBeDefined();
    await user.click(screen.getByRole("button", { name: "引用" }));
    expect(screen.getByRole("heading", { name: "引用" })).toBeDefined();
  });

  test("shows only subtle reposter context and renders a web-style quoted post card", async () => {
    const openUser = mock(() => undefined);
    const openQuotedPost = mock(() => undefined);
    const user = userEvent.setup();
    const socialPost: TimelinePost = {
      ...post(),
      id: "250",
      text: "Source post",
      author: {
        id: "25",
        username: "origin",
        displayName: "Original Author",
        avatarUrl: null,
        verified: false,
      },
      repostedBy: {
        id: "30",
        username: "reposter",
        displayName: "Reposter",
        avatarUrl: null,
        verified: false,
      },
      quotedPost: {
        id: "249",
        text: "Quoted source",
        language: "ja",
        createdAt: null,
        author: {
          id: "24",
          username: "quoted",
          displayName: "Quoted Author",
          avatarUrl: null,
          verified: false,
        },
        media: [
          {
            id: "quote-photo",
            type: "photo",
            url: "https://pbs.twimg.com/quote.jpg",
            previewUrl: "https://pbs.twimg.com/quote.jpg",
          },
        ],
      },
    };

    render(
      <PostCard
        post={socialPost}
        accountId="account-1"
        translation={translate("ja")}
        onOpenUser={openUser}
        onOpenQuotedPost={openQuotedPost}
      />,
    );

    expect(screen.getByText("Source post")).toBeDefined();
    expect(screen.getByText("Original Author")).toBeDefined();
    expect(screen.queryByText(/RT @origin/)).toBeNull();
    const repostContext = screen.getByRole("button", { name: "Reposterさんがリポスト" });
    expect(repostContext.classList.contains("repost-context")).toBe(true);
    await user.click(repostContext);
    expect(openUser).toHaveBeenCalledWith("30");
    const quote = screen.getByRole("button", { name: /Quoted source/ });
    expect(quote.classList.contains("quoted-post-open")).toBe(true);
    expect(quote.closest(".quoted-post-card")).not.toBeNull();
    await user.click(quote);
    expect(openQuotedPost).toHaveBeenCalledWith("249");
  });

  test("automatically translates a fresh quoted post and keeps its original toggle internal", async () => {
    const openQuotedPost = mock(() => undefined);
    const requestedPostIds: string[] = [];
    globalThis.fetch = (async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/translation?")) {
        const postId = /\/posts\/([^/]+)\/translation/.exec(url)?.[1] ?? "unknown";
        requestedPostIds.push(postId);
        return Response.json({
          postId,
          sourceLanguage: "en",
          targetLanguage: "ja",
          text: "翻訳された引用元",
          provider: "X",
        });
      }
      return Response.json({ completed: true });
    }) as unknown as typeof fetch;
    const user = userEvent.setup();
    const quotedPost: TimelinePost = {
      ...post(),
      id: "quote-container",
      text: "引用コメント",
      quotedPost: {
        id: "quote-source",
        text: "Fresh quoted source",
        language: "en",
        createdAt: null,
        author: {
          id: "24",
          username: "quoted",
          displayName: "Quoted Author",
          avatarUrl: null,
          verified: false,
        },
        media: [],
      },
    };

    render(
      <PostTranslationProvider
        value={{ locale: "ja", autoTranslatePosts: true, setAutoTranslatePosts: () => undefined }}
      >
        <PostCard
          post={quotedPost}
          accountId="account-1"
          translation={translate("ja")}
          onOpenQuotedPost={openQuotedPost}
        />
      </PostTranslationProvider>,
    );

    expect(await screen.findByText("翻訳された引用元")).toBeDefined();
    expect(requestedPostIds).toEqual(["quote-source"]);
    await user.click(screen.getByRole("button", { name: "原文を表示" }));
    expect(screen.getByText("Fresh quoted source")).toBeDefined();
    expect(openQuotedPost).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: /Fresh quoted source/ }));
    expect(openQuotedPost).toHaveBeenCalledWith("quote-source");
  });

  test("uses an X pretranslation embedded in a quoted post without another request", async () => {
    let translationRequests = 0;
    globalThis.fetch = (async (input: RequestInfo | URL) => {
      if (String(input).includes("/translation?")) translationRequests += 1;
      return new Response(null, { status: 500 });
    }) as unknown as typeof fetch;

    render(
      <PostTranslationProvider
        value={{ locale: "ja", autoTranslatePosts: true, setAutoTranslatePosts: () => undefined }}
      >
        <PostCard
          post={{
            ...post(),
            quotedPost: {
              id: "quote-pretranslated",
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
                text: "事前翻訳された引用元",
                sourceLanguage: "en",
                targetLanguage: "ja",
                provider: "Grok",
              },
              media: [],
            },
          }}
          accountId="account-1"
          translation={translate("ja")}
        />
      </PostTranslationProvider>,
    );

    expect(screen.getByText("事前翻訳された引用元")).toBeDefined();
    expect(screen.getByText("Grokによる自動翻訳")).toBeDefined();
    expect(translationRequests).toBe(0);
  });

  test("honors persistent media preview, autoplay, loop, and volume settings", async () => {
    const mediaPost = {
      ...post(),
      media: [
        {
          id: "video-1",
          type: "video",
          url: "https://video.example/video.mp4",
          previewUrl: "https://video.example/preview.jpg",
        },
      ],
    };
    const first = render(
      <PostCard
        post={mediaPost}
        accountId="account-1"
        translation={translate("ja")}
        display={{ ...defaultDisplayPreferences, mediaPreview: false }}
      />,
    );

    expect(screen.getByRole("link", { name: "メディアを表示" })).toBeDefined();
    expect(first.container.querySelector("video")).toBeNull();
    first.unmount();

    const second = render(
      <PostCard
        post={mediaPost}
        accountId="account-1"
        translation={translate("ja")}
        display={{ ...defaultDisplayPreferences, videoAutoplay: true }}
      />,
    );
    const video = second.container.querySelector("video");
    expect(video?.autoplay).toBe(true);
    expect(video?.loop).toBe(true);
    expect(video?.volume).toBe(1);
    expect(video?.muted).toBe(true);

    second.rerender(
      <PostCard
        post={mediaPost}
        accountId="account-1"
        translation={translate("ja")}
        display={{
          ...defaultDisplayPreferences,
          videoAutoplay: true,
          videoLoop: false,
          videoVolume: 35,
        }}
      />,
    );
    await waitFor(() => expect(second.container.querySelector("video")?.volume).toBe(0.35));
    expect(second.container.querySelector("video")?.loop).toBe(false);
    expect(second.container.querySelector("video")?.muted).toBe(true);

    second.rerender(
      <PostCard
        post={mediaPost}
        accountId="account-1"
        translation={translate("ja")}
        display={{ ...defaultDisplayPreferences, videoVolume: 0 }}
      />,
    );
    await waitFor(() => expect(second.container.querySelector("video")?.muted).toBe(true));
    expect(second.container.querySelector("video")?.volume).toBe(0);
  });

  test("executes follow from the post menu through the authenticated local API", async () => {
    let requestedUrl = "";
    globalThis.fetch = (async (input) => {
      requestedUrl = String(input);
      return Response.json({ userId: "42", action: "follow" });
    }) as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    await user.click(screen.getByLabelText("ポストメニュー"));
    await user.click(screen.getByRole("button", { name: "フォロー" }));

    await screen.findByRole("button", { name: "フォロー · 完了" });
    expect(requestedUrl).toContain("/api/v1/users/42/actions/follow?accountId=account-1");
  });

  test("adds the post author to a selected list through the web mutation", async () => {
    let requestedUrl = "";
    globalThis.fetch = (async (input) => {
      requestedUrl = String(input);
      return Response.json({ userId: "42", listId: "84", action: "add" });
    }) as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    await user.click(screen.getByLabelText("ポストメニュー"));
    await user.click(screen.getByRole("button", { name: "リストから追加と削除" }));
    await user.type(screen.getByLabelText("リストID"), "84");
    await user.click(screen.getByRole("button", { name: "リストに追加" }));

    await screen.findByRole("button", { name: "リストに追加 · 完了" });
    expect(requestedUrl).toContain("/api/v1/users/42/lists/84/add?accountId=account-1");
  });

  test("opens details by clicking or pressing Enter on the post card surface", async () => {
    const onOpen = mock(() => undefined);
    const user = userEvent.setup();
    render(
      <PostCard
        post={post()}
        accountId="account-1"
        translation={translate("ja")}
        onOpen={onOpen}
      />,
    );
    const card = screen.getByRole("article", { name: "ポストの詳細" });

    await user.click(card);
    card.focus();
    await user.keyboard("{Enter}");

    expect(onOpen).toHaveBeenCalledTimes(2);
  });

  test("opens the internal user profile from the author identity", async () => {
    const openUser = mock(() => undefined);
    const user = userEvent.setup();
    render(
      <PostCard
        post={post()}
        accountId="account-1"
        translation={translate("ja")}
        onOpenUser={openUser}
      />,
    );

    await user.click(screen.getByRole("button", { name: /Alice/ }));

    expect(openUser).toHaveBeenCalledWith("42");
  });

  test("shows reply context and opens the parent post inside NyTweetDeck", async () => {
    let openedPostId = "";
    const user = userEvent.setup();
    render(
      <PostCard
        post={{ ...post(), replyToPostId: "99", replyToUsername: "parent" }}
        accountId="account-1"
        translation={translate("ja")}
        onOpenQuotedPost={(postId) => {
          openedPostId = postId;
        }}
      />,
    );

    await user.click(screen.getByRole("button", { name: "返信先: @parent" }));

    expect(openedPostId).toBe("99");
  });

  test("automatically translates a foreign-language post and toggles original and global state", async () => {
    globalThis.fetch = (async (input) => {
      if (String(input).includes("/translation?")) {
        return Response.json({
          postId: "900",
          sourceLanguage: "en",
          targetLanguage: "ja",
          text: "自動翻訳された本文",
          provider: "X",
        });
      }
      return Response.json({ completed: true });
    }) as typeof fetch;
    const user = userEvent.setup();

    function Harness() {
      const [enabled, setEnabled] = useState(true);
      return (
        <PostTranslationProvider
          value={{ locale: "ja", autoTranslatePosts: enabled, setAutoTranslatePosts: setEnabled }}
        >
          <PostCard
            post={{ ...post(), id: "900", language: "en", text: "Original post" }}
            accountId="account-1"
            translation={translate("ja")}
          />
        </PostTranslationProvider>
      );
    }

    render(<Harness />);

    expect(await screen.findByText("自動翻訳された本文")).toBeDefined();
    expect(screen.getByText("Xによる自動翻訳")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "原文を表示" }));
    expect(screen.getByText("Original post")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "翻訳を表示" }));
    expect(screen.getByText("自動翻訳された本文")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "すべてのカラムで自動翻訳をオフにする" }));
    expect(screen.getByText("Original post")).toBeDefined();
    expect(
      screen.getByRole("button", { name: "すべてのカラムで自動翻訳をオンにする" }),
    ).toBeDefined();
  });

  test("renders X pretranslation immediately without requesting the translation endpoint", async () => {
    let requests = 0;
    globalThis.fetch = (async () => {
      requests += 1;
      return new Response(null, { status: 500 });
    }) as unknown as typeof fetch;
    const user = userEvent.setup();

    render(
      <PostTranslationProvider
        value={{ locale: "ja", autoTranslatePosts: true, setAutoTranslatePosts: () => undefined }}
      >
        <PostCard
          post={{
            ...post(),
            id: "920",
            language: "en",
            text: "Fresh original",
            preTranslated: {
              text: "事前に翻訳された本文",
              sourceLanguage: "en",
              targetLanguage: "ja",
              provider: "Grok",
            },
          }}
          accountId="account-1"
          translation={translate("ja")}
        />
      </PostTranslationProvider>,
    );

    expect(screen.getByText("事前に翻訳された本文")).toBeDefined();
    expect(screen.getByText("Grokによる自動翻訳")).toBeDefined();
    expect(requests).toBe(0);
    await user.click(screen.getByRole("button", { name: "原文を表示" }));
    expect(screen.getByText("Fresh original")).toBeDefined();
  });

  test("defers real-time translation until a post approaches the viewport", async () => {
    let callback: IntersectionObserverCallback | null = null;
    let requests = 0;
    globalThis.IntersectionObserver = class {
      readonly root = null;
      readonly rootMargin = "600px 0px";
      readonly thresholds = [0];

      constructor(observerCallback: IntersectionObserverCallback) {
        callback = observerCallback;
      }

      disconnect() {}
      observe() {}
      takeRecords(): IntersectionObserverEntry[] {
        return [];
      }
      unobserve() {}
    } as unknown as typeof IntersectionObserver;
    globalThis.fetch = (async () => {
      requests += 1;
      return Response.json({
        postId: "viewport-post",
        sourceLanguage: "en",
        targetLanguage: "ja",
        text: "表示範囲で翻訳",
        provider: "X",
      });
    }) as unknown as typeof fetch;

    render(
      <PostTranslationProvider
        value={{ locale: "ja", autoTranslatePosts: true, setAutoTranslatePosts: () => undefined }}
      >
        <PostCard
          post={{ ...post(), id: "viewport-post", language: "en", text: "Offscreen original" }}
          accountId="account-1"
          translation={translate("ja")}
        />
      </PostTranslationProvider>,
    );

    expect(requests).toBe(0);
    if (callback === null) throw new Error("IntersectionObserverが作成されませんでした。");
    act(() => {
      callback?.(
        [{ isIntersecting: true } as IntersectionObserverEntry],
        {} as IntersectionObserver,
      );
    });

    expect(await screen.findByText("表示範囲で翻訳")).toBeDefined();
    expect(requests).toBe(1);
  });

  test("applies the gear setting to every post and retries a failed translation", async () => {
    const attempts = new Map<string, number>();
    globalThis.fetch = (async (input) => {
      const url = String(input);
      const postId = url.includes("/911/") ? "911" : "910";
      const count = (attempts.get(postId) ?? 0) + 1;
      attempts.set(postId, count);
      if (postId === "910" && count === 1) return new Response(null, { status: 400 });
      return Response.json({
        postId,
        sourceLanguage: "en",
        targetLanguage: "ja",
        text: `翻訳 ${postId}`,
        provider: "X",
      });
    }) as typeof fetch;
    const user = userEvent.setup();

    function Harness() {
      const [enabled, setEnabled] = useState(true);
      return (
        <PostTranslationProvider
          value={{ locale: "ja", autoTranslatePosts: enabled, setAutoTranslatePosts: setEnabled }}
        >
          <PostCard
            post={{ ...post(), id: "910", language: "en", text: "Original 910" }}
            accountId="account-1"
            translation={translate("ja")}
          />
          <PostCard
            post={{ ...post(), id: "911", language: "en", text: "Original 911" }}
            accountId="account-1"
            translation={translate("ja")}
          />
        </PostTranslationProvider>
      );
    }

    render(<Harness />);

    expect(await screen.findByText("翻訳できませんでした。原文を表示しています。")).toBeDefined();
    expect(await screen.findByText("翻訳 911")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "再試行" }));
    expect(await screen.findByText("翻訳 910")).toBeDefined();
    await user.click(
      screen.getAllByRole("button", {
        name: "すべてのカラムで自動翻訳をオフにする",
      })[0] as HTMLButtonElement,
    );
    expect(screen.getByText("Original 910")).toBeDefined();
    expect(screen.getByText("Original 911")).toBeDefined();
    expect(
      screen.getAllByRole("button", {
        name: "すべてのカラムで自動翻訳をオンにする",
      }),
    ).toHaveLength(2);
  });
});

function post(): TimelinePost {
  return {
    id: "100",
    text: "post",
    language: "ja",
    createdAt: null,
    author: { id: "42", username: "alice", displayName: "Alice", avatarUrl: null, verified: false },
    repostedBy: null,
    replyCount: 1,
    repostCount: 2,
    quoteCount: 0,
    likeCount: 3,
    bookmarkCount: 4,
    viewCount: 5,
    liked: false,
    reposted: false,
    bookmarked: false,
    replyToPostId: null,
    replyToUsername: null,
    quotedPost: null,
    media: [],
  };
}
