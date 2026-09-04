import { afterEach, beforeEach, describe, expect, mock, test } from "bun:test";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { translate } from "../i18n/translations";
import { defaultDisplayPreferences } from "../model/layout";
import { PostCard, type TimelinePost } from "./post-card";
import { PostTranslationProvider } from "./post-translation-context";

const originalFetch = globalThis.fetch;
const originalIntersectionObserver = globalThis.IntersectionObserver;
const originalVideoPlay = globalThis.HTMLMediaElement.prototype.play;
const originalVideoPause = globalThis.HTMLMediaElement.prototype.pause;
const originalVideoLoad = globalThis.HTMLMediaElement.prototype.load;

beforeEach(() => {
  globalThis.IntersectionObserver = undefined as unknown as typeof IntersectionObserver;
});

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
  globalThis.IntersectionObserver = originalIntersectionObserver;
  globalThis.HTMLMediaElement.prototype.play = originalVideoPlay;
  globalThis.HTMLMediaElement.prototype.pause = originalVideoPause;
  globalThis.HTMLMediaElement.prototype.load = originalVideoLoad;
});

describe("post actions", () => {
  test("updates a like immediately while the web mutation continues in the background", async () => {
    const urls: string[] = [];
    let finishMutation: ((response: Response) => void) | undefined;
    globalThis.fetch = (async (input: Parameters<typeof fetch>[0]) => {
      urls.push(String(input));
      return new Promise<Response>((resolve) => {
        finishMutation = resolve;
      });
    }) as unknown as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    const likeButton = screen.getByRole("button", { name: "いいね" });
    expect(likeButton.textContent).toBe("3");
    await user.click(likeButton);
    expect(likeButton.textContent).toBe("4");
    expect(likeButton.classList.contains("like-active")).toBe(true);
    expect(likeButton.querySelector("svg")?.getAttribute("fill")).toBe("currentColor");
    expect((likeButton as HTMLButtonElement).disabled).toBe(false);
    expect(likeButton.getAttribute("aria-busy")).toBe("true");
    expect(screen.queryByText("ポスト操作に失敗しました。")).toBeNull();

    finishMutation?.(Response.json({ postId: "100", action: "like" }));
    await waitFor(() => expect((likeButton as HTMLButtonElement).disabled).toBe(false));

    globalThis.fetch = (async (input) => {
      urls.push(String(input));
      return Response.json({ postId: "100", action: "unlike" });
    }) as typeof fetch;
    await user.click(likeButton);
    expect(likeButton.textContent).toBe("3");
    expect(likeButton.classList.contains("like-active")).toBe(false);
    expect(likeButton.querySelector("svg")?.getAttribute("fill")).toBe("none");

    expect(urls[0]).toContain("/posts/100/actions/like?accountId=account-1");
    expect(urls[1]).toContain("/posts/100/actions/unlike?accountId=account-1");
  });

  test("accepts rapid toggles immediately and serializes only the states needed to converge", async () => {
    const urls: string[] = [];
    const finishMutations: Array<(response: Response) => void> = [];
    globalThis.fetch = (async (input) => {
      urls.push(String(input));
      return new Promise<Response>((resolve) => finishMutations.push(resolve));
    }) as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);
    const likeButton = screen.getByRole("button", { name: "いいね" });

    await user.click(likeButton);
    expect(likeButton.textContent).toBe("4");
    await user.click(likeButton);
    expect(likeButton.textContent).toBe("3");
    expect(likeButton.classList.contains("like-active")).toBe(false);
    expect(finishMutations).toHaveLength(1);

    finishMutations[0]?.(Response.json({ postId: "100", action: "like" }));
    await waitFor(() => expect(finishMutations).toHaveLength(2));
    expect(likeButton.textContent).toBe("3");
    finishMutations[1]?.(Response.json({ postId: "100", action: "unlike" }));
    await waitFor(() => expect(likeButton.getAttribute("aria-busy")).toBe("false"));

    expect(urls[0]).toContain("/actions/like?");
    expect(urls[1]).toContain("/actions/unlike?");
  });

  test("reconciles live counts without overwriting a pending optimistic intent", async () => {
    let finishMutation: ((response: Response) => void) | undefined;
    globalThis.fetch = (async () =>
      new Promise<Response>((resolve) => {
        finishMutation = resolve;
      })) as unknown as typeof fetch;
    const user = userEvent.setup();
    const view = render(
      <PostCard post={post()} accountId="account-1" translation={translate("ja")} />,
    );
    const likeButton = screen.getByRole("button", { name: "いいね" });

    await user.click(likeButton);
    view.rerender(
      <PostCard
        post={{ ...post(), likeCount: 4 }}
        accountId="account-1"
        translation={translate("ja")}
      />,
    );

    expect(likeButton.textContent).toBe("5");
    expect(likeButton.classList.contains("like-active")).toBe(true);
    finishMutation?.(Response.json({ postId: "100", action: "like" }));
    await waitFor(() => expect(likeButton.getAttribute("aria-busy")).toBe("false"));
    expect(likeButton.textContent).toBe("5");
  });

  test("coalesces a superseded rapid toggle without sending an unnecessary inverse request", async () => {
    let finishMutation: ((response: Response) => void) | undefined;
    let requests = 0;
    globalThis.fetch = (async () => {
      requests += 1;
      return new Promise<Response>((resolve) => {
        finishMutation = resolve;
      });
    }) as unknown as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);
    const likeButton = screen.getByRole("button", { name: "いいね" });

    await user.click(likeButton);
    await user.click(likeButton);
    await user.click(likeButton);
    expect(likeButton.textContent).toBe("4");
    expect(requests).toBe(1);
    finishMutation?.(Response.json({ postId: "100", action: "like" }));

    await waitFor(() => expect(likeButton.getAttribute("aria-busy")).toBe("false"));
    expect(requests).toBe(1);
    expect(likeButton.classList.contains("like-active")).toBe(true);
  });

  test("colors engagement controls from the initially loaded X state", () => {
    render(
      <PostCard
        post={{ ...post(), liked: true, reposted: true, bookmarked: true }}
        accountId="account-1"
        translation={translate("ja")}
      />,
    );

    const likeButton = screen.getByRole("button", { name: "いいね" });
    const repostButton = screen.getByRole("button", { name: "リポスト" });
    const bookmarkButton = document.querySelector('[data-post-action="bookmark"]');
    if (!(bookmarkButton instanceof HTMLButtonElement)) {
      throw new Error("履歴保存ボタンが見つかりません。");
    }
    expect(likeButton.classList.contains("like-active")).toBe(true);
    expect(likeButton.querySelector("svg")?.getAttribute("fill")).toBe("currentColor");
    expect(repostButton.classList.contains("repost-active")).toBe(true);
    expect(bookmarkButton.classList.contains("bookmark-active")).toBe(true);
    expect(bookmarkButton.querySelector("svg")?.getAttribute("fill")).toBe("currentColor");
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

  test("rolls an optimistic like back when the mutation fails", async () => {
    let failMutation: ((response: Response) => void) | undefined;
    globalThis.fetch = (async () =>
      new Promise<Response>((resolve) => {
        failMutation = resolve;
      })) as unknown as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    const likeButton = screen.getByRole("button", { name: "いいね" });
    await user.click(likeButton);

    expect(likeButton.textContent).toBe("4");
    expect(likeButton.classList.contains("like-active")).toBe(true);
    failMutation?.(
      Response.json({ detail: "X Web署名情報を取得できませんでした。" }, { status: 502 }),
    );

    await screen.findByText("ポスト操作に失敗しました。 X Web署名情報を取得できませんでした。");
    expect(likeButton.textContent).toBe("3");
    expect(likeButton.classList.contains("like-active")).toBe(false);
  });

  test("updates a repost immediately and rolls it back on failure", async () => {
    let failMutation: ((response: Response) => void) | undefined;
    globalThis.fetch = (async () =>
      new Promise<Response>((resolve) => {
        failMutation = resolve;
      })) as unknown as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    const repostToggle = screen.getByRole("button", { name: "リポスト" });
    await user.click(repostToggle);
    await user.click(screen.getByRole("menuitem", { name: "リポスト" }));

    expect(repostToggle.textContent).toBe("3");
    expect(repostToggle.classList.contains("repost-active")).toBe(true);
    failMutation?.(new Response(null, { status: 503 }));

    await screen.findByText("ポスト操作に失敗しました。");
    expect(repostToggle.textContent).toBe("2");
    expect(repostToggle.classList.contains("repost-active")).toBe(false);
  });

  test("opens reply composer and the complete overflow menu", async () => {
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    await user.click(screen.getByRole("button", { name: "返信" }));
    expect(screen.getByRole("heading", { name: "返信" })).toBeDefined();
    await user.click(screen.getByRole("button", { name: "閉じる" }));
    const overflowTrigger = screen.getByRole("button", { name: "ポストメニュー" });
    expect(overflowTrigger.getAttribute("aria-expanded")).toBe("false");
    await user.click(overflowTrigger);

    expect(overflowTrigger.getAttribute("aria-expanded")).toBe("true");
    expect(screen.getByRole("menu")).toBeDefined();
    expect(screen.getByRole("menuitem", { name: "このポストに興味がない" })).toBeDefined();
    expect(screen.getByRole("menuitem", { name: "コミュニティノートリクエスト" })).toBeDefined();
    await user.keyboard("{Escape}");
    expect(overflowTrigger.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByRole("menuitem", { name: "このポストに興味がない" })).toBeNull();
  });

  test("shows the at-username and hashtags and offers repost and quote choices", async () => {
    const user = userEvent.setup();
    const taggedPost = { ...post(), text: "hello #NyTweetDeck" };
    render(<PostCard post={taggedPost} accountId="account-1" translation={translate("ja")} />);

    expect(screen.getByText("@alice")).toBeDefined();
    expect(screen.queryByText("42")).toBeNull();
    expect(screen.getByText("#NyTweetDeck").classList.contains("hashtag")).toBe(true);
    const repostTrigger = screen.getByRole("button", { name: "リポスト" });
    expect(repostTrigger.getAttribute("aria-expanded")).toBe("false");
    await user.click(repostTrigger);
    expect(repostTrigger.getAttribute("aria-expanded")).toBe("true");
    expect(screen.getByRole("menuitem", { name: "リポスト" })).toBeDefined();
    await user.keyboard("{Escape}");
    expect(repostTrigger.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByRole("menuitem", { name: "引用" })).toBeNull();
    await user.click(repostTrigger);
    await user.click(screen.getByRole("menuitem", { name: "引用" }));
    expect(screen.getByRole("heading", { name: "引用" })).toBeDefined();
  });

  test("links normalized HTTP URLs without opening the post detail", async () => {
    const onOpen = mock(() => undefined);
    const user = userEvent.setup();
    render(
      <PostCard
        post={{ ...post(), text: "参照 https://example.test/path). #NyTD" }}
        accountId="account-1"
        translation={translate("ja")}
        onOpen={onOpen}
      />,
    );

    const link = screen.getByRole("link", { name: "https://example.test/path" });
    expect(link.getAttribute("href")).toBe("https://example.test/path");
    expect(link.nextSibling?.textContent).toContain("). ");
    link.addEventListener("click", (event) => event.preventDefault(), { once: true });
    await user.click(link);
    expect(onOpen).not.toHaveBeenCalled();
  });

  test("uses X display text while keeping the expanded link destination", () => {
    render(
      <PostCard
        post={{
          ...post(),
          text: "参照 https://example.test/original?from=x",
          links: [
            {
              url: "https://example.test/original?from=x",
              displayText: "example.test/readable",
            },
          ],
        }}
        accountId="account-1"
        translation={translate("ja")}
      />,
    );

    const link = screen.getByRole("link", { name: "example.test/readable" });
    expect(link.getAttribute("href")).toBe("https://example.test/original?from=x");
  });

  test("hides a trailing media redirect and opens an X Article inside NyTweetDeck", async () => {
    const user = userEvent.setup();
    render(
      <PostCard
        post={{
          ...post(),
          text: "記事を公開しました https://t.co/article700",
          article: {
            id: "701",
            title: "NyTweetDeckの記事",
            previewText: "概要の最初の数行です。",
            body: "最初の段落。\n\n全文の続き。",
            coverImageUrl: "https://pbs.twimg.com/media/article-cover.jpg",
            url: "https://x.com/i/article/701",
          },
        }}
        accountId="account-1"
        translation={translate("ja")}
      />,
    );

    expect(screen.getByText("記事を公開しました")).toBeDefined();
    expect(screen.queryByText(/t\.co/)).toBeNull();
    expect(screen.getByText("概要の最初の数行です。")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "記事を読む: NyTweetDeckの記事" }));

    expect(screen.getByRole("dialog", { name: "NyTweetDeckの記事" })).toBeDefined();
    expect(screen.getByText(/全文の続き/)).toBeDefined();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "NyTweetDeckの記事" })).toBeNull();
    expect(screen.getByText("記事を公開しました")).toBeDefined();
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

  test("defers video loading and autoplay to the upper viewport while honoring settings", async () => {
    const observers = installIntersectionObserverMock();
    const user = userEvent.setup();
    const play = mock(async () => undefined);
    const pause = mock(() => undefined);
    const load = mock(() => undefined);
    globalThis.HTMLMediaElement.prototype.play = play as typeof HTMLMediaElement.prototype.play;
    globalThis.HTMLMediaElement.prototype.pause = pause as typeof HTMLMediaElement.prototype.pause;
    globalThis.HTMLMediaElement.prototype.load = load as typeof HTMLMediaElement.prototype.load;
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
    const videoUrl = mediaPost.media[0]?.url;
    if (videoUrl === undefined) throw new Error("動画URLがありません。");
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
    const player = screen.getByRole("group", { name: "動画プレイヤー" });
    const videoObserver = observers.find(
      (observer) => observer.options.rootMargin === "0px 0px -50% 0px",
    );
    if (videoObserver === undefined) throw new Error("動画用の監視が作成されませんでした。");
    expect(videoObserver.options.root).toBeNull();
    expect(videoObserver.options.threshold).toBe(0);
    expect(videoObserver.targets).toEqual([player]);
    expect(second.container.querySelector("video")).toBeNull();
    expect(player.querySelector(".configured-video-poster")).toBeDefined();
    expect(play).toHaveBeenCalledTimes(0);

    act(() => videoObserver.notify(player, true));
    await waitFor(() => expect(second.container.querySelector("video")).not.toBeNull());
    const firstVideo = second.container.querySelector("video");
    if (firstVideo === null) throw new Error("動画が表示されませんでした。");
    expect(firstVideo.getAttribute("src")).toBe(videoUrl);
    expect(firstVideo.controls).toBe(false);
    expect(firstVideo.hasAttribute("playsinline")).toBe(true);
    expect(firstVideo.loop).toBe(true);
    expect(firstVideo.volume).toBe(1);
    expect(firstVideo.muted).toBe(true);
    expect(firstVideo.autoplay).toBe(true);
    expect(firstVideo.preload).toBe("metadata");
    expect(play).toHaveBeenCalledTimes(1);

    act(() => videoObserver.notify(player, false));
    await waitFor(() => expect(second.container.querySelector("video")).toBeNull());
    expect(pause).toHaveBeenCalled();
    expect(load).toHaveBeenCalled();

    act(() => videoObserver.notify(player, true));
    await waitFor(() => expect(play).toHaveBeenCalledTimes(2));
    const replacementVideo = second.container.querySelector("video");
    if (replacementVideo === null) throw new Error("動画が再生成されませんでした。");
    expect(replacementVideo).not.toBe(firstVideo);
    expect(replacementVideo.getAttribute("src")).toBe(videoUrl);
    expect(replacementVideo.muted).toBe(true);

    Object.defineProperty(replacementVideo, "duration", { configurable: true, value: 125 });
    fireEvent.loadedMetadata(replacementVideo);
    expect(screen.getByText("0:00 / 2:05")).toBeDefined();
    const seek = screen.getByRole("slider", { name: "動画の再生位置" });
    const setPointerCapture = mock(() => undefined);
    Object.defineProperty(replacementVideo, "setPointerCapture", {
      configurable: true,
      value: setPointerCapture,
    });
    fireEvent.pointerDown(seek, { pointerId: 7 });
    expect(setPointerCapture).toHaveBeenCalledTimes(0);
    fireEvent.change(seek, { target: { value: "30" } });
    expect(replacementVideo.currentTime).toBe(30);
    expect((seek as HTMLInputElement).value).toBe("30");
    expect(replacementVideo.muted).toBe(false);

    fireEvent.pointerDown(replacementVideo, { pointerId: 8, clientX: 40, clientY: 20 });
    expect(setPointerCapture).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "動画を再生" }));
    expect(play).toHaveBeenCalledTimes(3);
    fireEvent.play(replacementVideo);
    await user.click(screen.getByRole("button", { name: "動画を一時停止" }));
    fireEvent.pause(replacementVideo);
    expect(screen.getByRole("button", { name: "動画を再生" })).toBeDefined();

    await user.click(screen.getByRole("button", { name: "動画をミュート" }));
    expect(replacementVideo.muted).toBe(true);
    const volumeControl = screen.getByRole("slider", { name: "動画の音量" });
    fireEvent.pointerDown(volumeControl, { pointerId: 9 });
    expect(setPointerCapture).toHaveBeenCalledTimes(1);
    expect(replacementVideo.muted).toBe(false);
    fireEvent.change(volumeControl, { target: { value: "25" } });
    expect(replacementVideo.volume).toBe(0.25);
    await user.click(screen.getByRole("button", { name: "動画をミュート" }));
    expect(replacementVideo.muted).toBe(true);

    const loopControl = screen.getByRole("button", { name: "動画をループ再生" });
    expect(loopControl.getAttribute("aria-pressed")).toBe("true");
    await user.click(loopControl);
    expect(replacementVideo.loop).toBe(false);
    await user.click(loopControl);
    expect(replacementVideo.loop).toBe(true);

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

  test("offers fullscreen and picture-in-picture through feature-detected custom controls", async () => {
    const observers = installIntersectionObserverMock();
    let fullscreenElement: Element | null = null;
    let pictureInPictureElement: Element | null = null;
    let pictureInPictureRequests = 0;
    const fullscreenElementDescriptor = Object.getOwnPropertyDescriptor(
      document,
      "fullscreenElement",
    );
    const exitFullscreenDescriptor = Object.getOwnPropertyDescriptor(document, "exitFullscreen");
    const pictureInPictureEnabledDescriptor = Object.getOwnPropertyDescriptor(
      document,
      "pictureInPictureEnabled",
    );
    const pictureInPictureElementDescriptor = Object.getOwnPropertyDescriptor(
      document,
      "pictureInPictureElement",
    );
    const exitPictureInPictureDescriptor = Object.getOwnPropertyDescriptor(
      document,
      "exitPictureInPicture",
    );
    const requestPictureInPictureDescriptor = Object.getOwnPropertyDescriptor(
      HTMLVideoElement.prototype,
      "requestPictureInPicture",
    );
    Object.defineProperty(document, "fullscreenElement", {
      configurable: true,
      get: () => fullscreenElement,
    });
    Object.defineProperty(document, "exitFullscreen", {
      configurable: true,
      value: async () => {
        fullscreenElement = null;
        document.dispatchEvent(new Event("fullscreenchange"));
      },
    });
    Object.defineProperty(document, "pictureInPictureEnabled", {
      configurable: true,
      value: true,
    });
    Object.defineProperty(document, "pictureInPictureElement", {
      configurable: true,
      get: () => pictureInPictureElement,
    });
    Object.defineProperty(document, "exitPictureInPicture", {
      configurable: true,
      value: async () => {
        const video = pictureInPictureElement;
        pictureInPictureElement = null;
        video?.dispatchEvent(new Event("leavepictureinpicture"));
      },
    });
    Object.defineProperty(HTMLVideoElement.prototype, "requestPictureInPicture", {
      configurable: true,
      value: async function (this: HTMLVideoElement) {
        pictureInPictureRequests += 1;
        pictureInPictureElement = this;
        this.dispatchEvent(new Event("enterpictureinpicture"));
        return {} as PictureInPictureWindow;
      },
    });
    const view = render(
      <PostCard
        post={{
          ...post(),
          media: [
            {
              id: "video-controls",
              type: "video",
              url: "https://video.example/controls.mp4",
              previewUrl: "https://video.example/controls.jpg",
            },
          ],
        }}
        accountId="account-1"
        translation={translate("ja")}
        display={{ ...defaultDisplayPreferences, videoAutoplay: false }}
      />,
    );
    try {
      const user = userEvent.setup();
      const player = screen.getByRole("group", { name: "動画プレイヤー" });
      Object.defineProperty(player, "requestFullscreen", {
        configurable: true,
        value: async () => {
          fullscreenElement = player;
          document.dispatchEvent(new Event("fullscreenchange"));
        },
      });
      const videoObserver = observers.find(
        (observer) => observer.options.rootMargin === "0px 0px -50% 0px",
      );
      if (videoObserver === undefined) throw new Error("動画用の監視が作成されませんでした。");
      act(() => videoObserver.notify(player, true));
      await waitFor(() => expect(view.container.querySelector("video")).not.toBeNull());
      expect(player.getAttribute("data-controls-visible")).toBe("true");
      await waitFor(() => expect(player.getAttribute("data-controls-visible")).toBe("false"), {
        timeout: 4_000,
      });
      fireEvent.pointerMove(player);
      expect(player.getAttribute("data-controls-visible")).toBe("true");

      await user.click(screen.getByRole("button", { name: "全画面表示にする" }));
      expect(screen.getByRole("button", { name: "全画面表示を終了" })).toBeDefined();
      await user.click(screen.getByRole("button", { name: "全画面表示を終了" }));
      expect(screen.getByRole("button", { name: "全画面表示にする" })).toBeDefined();
      Object.defineProperty(player, "requestFullscreen", {
        configurable: true,
        value: async () => Promise.reject(new Error("fullscreen unavailable")),
      });
      await user.click(screen.getByRole("button", { name: "全画面表示にする" }));
      await waitFor(() =>
        expect(player.querySelector(".configured-video-error")?.textContent).toBe(
          "動画操作に失敗しました。",
        ),
      );

      await user.click(
        await screen.findByRole("button", { name: "ピクチャーインピクチャーにする" }),
      );
      expect(pictureInPictureRequests).toBe(1);
      expect(screen.getByRole("button", { name: "ピクチャーインピクチャーを終了" })).toBeDefined();
      act(() => videoObserver.notify(player, false));
      expect(view.container.querySelector("video")).not.toBeNull();
      await user.click(screen.getByRole("button", { name: "ピクチャーインピクチャーを終了" }));
      await waitFor(() => expect(view.container.querySelector("video")).toBeNull());
    } finally {
      view.unmount();
      restoreProperty(document, "fullscreenElement", fullscreenElementDescriptor);
      restoreProperty(document, "exitFullscreen", exitFullscreenDescriptor);
      restoreProperty(document, "pictureInPictureEnabled", pictureInPictureEnabledDescriptor);
      restoreProperty(document, "pictureInPictureElement", pictureInPictureElementDescriptor);
      restoreProperty(document, "exitPictureInPicture", exitPictureInPictureDescriptor);
      restoreProperty(
        HTMLVideoElement.prototype,
        "requestPictureInPicture",
        requestPictureInPictureDescriptor,
      );
    }
  });

  test("completes independent user menu actions immediately while requests remain pending", async () => {
    const requestedUrls: string[] = [];
    const finishActions: Array<(response: Response) => void> = [];
    globalThis.fetch = (async (input) => {
      requestedUrls.push(String(input));
      return new Promise<Response>((resolve) => finishActions.push(resolve));
    }) as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    await user.click(screen.getByRole("button", { name: "ポストメニュー" }));
    await user.click(screen.getByRole("menuitem", { name: "フォロー" }));
    const followed = screen.getByRole("menuitem", { name: "フォロー · 完了" });
    expect(followed.getAttribute("aria-busy")).toBe("true");
    await user.click(screen.getByRole("menuitem", { name: "ミュート" }));

    expect(screen.getByRole("menuitem", { name: "ミュート · 完了" })).toBeDefined();
    expect(requestedUrls).toHaveLength(2);
    expect(requestedUrls[0]).toContain("/api/v1/users/42/actions/follow?accountId=account-1");
    expect(requestedUrls[1]).toContain("/api/v1/users/42/actions/mute?accountId=account-1");
    finishActions[0]?.(Response.json({ userId: "42", action: "follow" }));
    finishActions[1]?.(Response.json({ userId: "42", action: "mute" }));
    await waitFor(() => expect(followed.getAttribute("aria-busy")).toBe("false"));
  });

  test("rolls back only the failed optimistic user menu action", async () => {
    let finishAction: ((response: Response) => void) | undefined;
    globalThis.fetch = (async () =>
      new Promise<Response>((resolve) => {
        finishAction = resolve;
      })) as unknown as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    await user.click(screen.getByRole("button", { name: "ポストメニュー" }));
    await user.click(screen.getByRole("menuitem", { name: "フォロー" }));
    expect(screen.getByRole("menuitem", { name: "フォロー · 完了" })).toBeDefined();
    finishAction?.(new Response(null, { status: 503 }));

    await screen.findByText("ユーザー操作に失敗しました。");
    expect(screen.getByRole("menuitem", { name: "フォロー" })).toBeDefined();
    expect(screen.getByRole("menuitem", { name: "ミュート" })).toBeDefined();
  });

  test("shows the latest list intent immediately and sends rapid operations in order", async () => {
    const requestedUrls: string[] = [];
    const finishActions: Array<(response: Response) => void> = [];
    globalThis.fetch = (async (input) => {
      requestedUrls.push(String(input));
      return new Promise<Response>((resolve) => finishActions.push(resolve));
    }) as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    await user.click(screen.getByRole("button", { name: "ポストメニュー" }));
    await user.click(screen.getByRole("menuitem", { name: "リストから追加と削除" }));
    await user.type(screen.getByLabelText("リストID"), "84");
    await user.click(screen.getByRole("menuitem", { name: "リストに追加" }));
    expect(screen.getByRole("menuitem", { name: "リストに追加 · 完了" })).toBeDefined();
    await user.click(screen.getByRole("menuitem", { name: "リストから削除" }));

    expect(screen.getByRole("menuitem", { name: "リストから削除 · 完了" })).toBeDefined();
    expect(requestedUrls).toHaveLength(1);
    finishActions[0]?.(Response.json({ userId: "42", listId: "84", action: "add" }));
    await waitFor(() => expect(requestedUrls).toHaveLength(2));
    finishActions[1]?.(Response.json({ userId: "42", listId: "84", action: "remove" }));
    await waitFor(() =>
      expect(
        screen.getByRole("menuitem", { name: "リストから削除 · 完了" }).getAttribute("aria-busy"),
      ).toBe("false"),
    );
    expect(requestedUrls[0]).toContain("/api/v1/users/42/lists/84/add?accountId=account-1");
    expect(requestedUrls[1]).toContain("/api/v1/users/42/lists/84/remove?accountId=account-1");
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

interface RecordedIntersectionObserver {
  callback: IntersectionObserverCallback;
  options: IntersectionObserverInit;
  targets: Element[];
  notify(target: Element, isIntersecting: boolean): void;
}

function installIntersectionObserverMock(): RecordedIntersectionObserver[] {
  const observers: RecordedIntersectionObserver[] = [];
  globalThis.IntersectionObserver = class {
    readonly root: Element | Document | null;
    readonly rootMargin: string;
    readonly thresholds: number[];
    private readonly record: RecordedIntersectionObserver;

    constructor(callback: IntersectionObserverCallback, options: IntersectionObserverInit = {}) {
      this.root = options.root ?? null;
      this.rootMargin = options.rootMargin ?? "0px";
      this.thresholds = Array.isArray(options.threshold)
        ? [...options.threshold]
        : [options.threshold ?? 0];
      this.record = {
        callback,
        options,
        targets: [],
        notify: (target, isIntersecting) =>
          callback(
            [{ target, isIntersecting } as IntersectionObserverEntry],
            this as unknown as IntersectionObserver,
          ),
      };
      observers.push(this.record);
    }

    disconnect() {
      this.record.targets.length = 0;
    }

    observe(target: Element) {
      this.record.targets.push(target);
    }

    takeRecords(): IntersectionObserverEntry[] {
      return [];
    }

    unobserve(target: Element) {
      this.record.targets = this.record.targets.filter((item) => item !== target);
    }
  } as unknown as typeof IntersectionObserver;
  return observers;
}

function restoreProperty(
  target: object,
  key: PropertyKey,
  descriptor: PropertyDescriptor | undefined,
) {
  if (descriptor === undefined) Reflect.deleteProperty(target, key);
  else Object.defineProperty(target, key, descriptor);
}

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
