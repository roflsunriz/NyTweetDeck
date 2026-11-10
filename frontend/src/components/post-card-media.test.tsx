import { PostTranslationProvider } from "./post-translation-context";
import { installIntersectionObserverMock, restoreProperty, post } from "./post-card-test-helpers";
import { afterEach, beforeEach, describe, expect, jest, mock, test } from "bun:test";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { defaultDisplayPreferences } from "../model/layout";
import { VIDEO_STALL_RECOVERY_DELAY_MS } from "./configured-video";
import { PostCard, type TimelinePost } from "./post-card";

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
  jest.useRealTimers();
  globalThis.fetch = originalFetch;
  globalThis.IntersectionObserver = originalIntersectionObserver;
  globalThis.HTMLMediaElement.prototype.play = originalVideoPlay;
  globalThis.HTMLMediaElement.prototype.pause = originalVideoPause;
  globalThis.HTMLMediaElement.prototype.load = originalVideoLoad;
});

describe("post card media and quoted content", () => {
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
    expect(screen.getByText(/から翻訳/u)).toBeDefined();
    expect(translationRequests).toBe(0);
  });

  test("preloads nearby video and limits autoplay to the upper viewport while honoring settings", async () => {
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
      (observer) =>
        observer.options.rootMargin === "0px 0px -50% 0px" && observer.targets.includes(player),
    );
    const preloadObserver = observers.find(
      (observer) =>
        observer.options.rootMargin === "1200px 0px" && observer.targets.includes(player),
    );
    if (videoObserver === undefined) throw new Error("動画用の監視が作成されませんでした。");
    if (preloadObserver === undefined)
      throw new Error("動画先読み用の監視が作成されませんでした。");
    expect(videoObserver.options.root).toBeNull();
    expect(videoObserver.options.threshold).toBe(0);
    expect(videoObserver.targets).toEqual([player]);
    expect(second.container.querySelector("video")).toBeNull();
    expect(player.querySelector(".configured-video-poster")).toBeDefined();
    expect(play).toHaveBeenCalledTimes(0);

    act(() => preloadObserver.notify(player, true));
    await waitFor(() => expect(second.container.querySelector("video")).not.toBeNull());
    const firstVideo = second.container.querySelector("video");
    if (firstVideo === null) throw new Error("動画が表示されませんでした。");
    expect(firstVideo.getAttribute("src")).toBe(videoUrl);
    expect(firstVideo.controls).toBe(false);
    expect(firstVideo.getAttribute("draggable")).toBe("false");
    expect(firstVideo.hasAttribute("playsinline")).toBe(true);
    expect(fireEvent.dragStart(firstVideo)).toBe(false);
    expect(firstVideo.loop).toBe(true);
    expect(firstVideo.volume).toBe(1);
    expect(firstVideo.muted).toBe(true);
    expect(firstVideo.autoplay).toBe(false);
    expect(firstVideo.preload).toBe("auto");
    expect(play).toHaveBeenCalledTimes(0);

    act(() => videoObserver.notify(player, true));
    await waitFor(() => expect(play).toHaveBeenCalledTimes(1));
    expect(firstVideo.autoplay).toBe(true);
    expect(play).toHaveBeenCalledTimes(1);

    act(() => videoObserver.notify(player, false));
    await waitFor(() => expect(pause).toHaveBeenCalled());
    expect(second.container.querySelector("video")).toBe(firstVideo);

    act(() => preloadObserver.notify(player, false));
    await waitFor(() => expect(second.container.querySelector("video")).toBeNull());
    expect(load).toHaveBeenCalled();

    act(() => preloadObserver.notify(player, true));
    await waitFor(() => expect(second.container.querySelector("video")).not.toBeNull());
    const replacementVideo = second.container.querySelector("video");
    if (replacementVideo === null) throw new Error("動画が再生成されませんでした。");
    expect(replacementVideo).not.toBe(firstVideo);
    expect(replacementVideo.getAttribute("src")).toBe(videoUrl);
    expect(replacementVideo.muted).toBe(true);

    act(() => videoObserver.notify(player, true));
    await waitFor(() => expect(play).toHaveBeenCalledTimes(2));

    Object.defineProperty(replacementVideo, "duration", { configurable: true, value: 125 });
    fireEvent.loadedMetadata(replacementVideo);
    expect(screen.getByText("00:00 / 02:05")).toBeDefined();
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

  test("falls back after a stalled automatic source and restores playback position", () => {
    jest.useFakeTimers();
    try {
      const play = mock(async () => undefined);
      globalThis.HTMLMediaElement.prototype.play = play as typeof HTMLMediaElement.prototype.play;
      const view = render(
        <PostCard
          post={{
            ...post(),
            media: [
              {
                id: "adaptive-video",
                type: "video",
                url: "https://video.example/high.mp4",
                previewUrl: "https://video.example/poster.jpg",
                variants: [
                  { url: "https://video.example/low.mp4", bitrate: 256_000 },
                  { url: "https://video.example/mid.mp4", bitrate: 832_000 },
                  { url: "https://video.example/high.mp4", bitrate: 2_000_000 },
                ],
              },
            ],
          }}
          accountId="account-1"
          translation={translate("ja")}
          display={{ ...defaultDisplayPreferences, videoAutoplay: false, videoQuality: "auto" }}
        />,
      );
      const preferred = view.container.querySelector("video");
      if (preferred === null) throw new Error("動画が作成されませんでした。");
      expect(preferred.getAttribute("src")).toBe("https://video.example/high.mp4");
      preferred.currentTime = 42;
      Object.defineProperty(preferred, "paused", { configurable: true, value: false });

      fireEvent.stalled(preferred);
      act(() => jest.advanceTimersByTime(VIDEO_STALL_RECOVERY_DELAY_MS));

      const recovered = view.container.querySelector("video");
      if (recovered === null) throw new Error("復旧後の動画が作成されませんでした。");
      expect(recovered.getAttribute("src")).toBe("https://video.example/mid.mp4");
      Object.defineProperty(recovered, "duration", { configurable: true, value: 120 });
      fireEvent.loadedMetadata(recovered);
      expect(recovered.currentTime).toBe(42);
      expect(play).toHaveBeenCalledTimes(1);
    } finally {
      jest.useRealTimers();
    }
  });
});
