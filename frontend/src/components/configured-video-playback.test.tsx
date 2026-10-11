import { afterEach, expect, spyOn, test } from "bun:test";
import { act, cleanup, fireEvent, render } from "@testing-library/react";
import { translate } from "../i18n/translations";
import { resetVideoPlaybackForTesting } from "../model/video-playback-coordinator";
import { ConfiguredVideo } from "./configured-video";

const originalObserver = globalThis.IntersectionObserver;
const originalPlay = HTMLMediaElement.prototype.play;
const originalPause = HTMLMediaElement.prototype.pause;
const originalLoad = HTMLMediaElement.prototype.load;
const wakeLockDescriptor = Object.getOwnPropertyDescriptor(navigator, "wakeLock");

afterEach(() => {
  cleanup();
  resetVideoPlaybackForTesting();
  globalThis.IntersectionObserver = originalObserver;
  HTMLMediaElement.prototype.play = originalPlay;
  HTMLMediaElement.prototype.pause = originalPause;
  HTMLMediaElement.prototype.load = originalLoad;
  if (wakeLockDescriptor) Object.defineProperty(navigator, "wakeLock", wakeLockDescriptor);
  else Reflect.deleteProperty(navigator, "wakeLock");
});

function installHarness() {
  const observers: Array<{ callback: IntersectionObserverCallback; targets: Set<Element> }> = [];
  globalThis.IntersectionObserver = class {
    record: { callback: IntersectionObserverCallback; targets: Set<Element> };
    constructor(callback: IntersectionObserverCallback) {
      this.record = { callback, targets: new Set() };
      observers.push(this.record);
    }
    observe(target: Element) {
      this.record.targets.add(target);
    }
    disconnect() {
      this.record.targets.clear();
    }
    unobserve(target: Element) {
      this.record.targets.delete(target);
    }
    takeRecords() {
      return [];
    }
  } as unknown as typeof IntersectionObserver;
  HTMLMediaElement.prototype.play = function () {
    Object.defineProperty(this, "paused", { configurable: true, value: false });
    this.dispatchEvent(new Event("play"));
    return Promise.resolve();
  };
  HTMLMediaElement.prototype.pause = function () {
    Object.defineProperty(this, "paused", { configurable: true, value: true });
    this.dispatchEvent(new Event("pause"));
  };
  HTMLMediaElement.prototype.load = () => {};
  return (active: boolean) => {
    for (const observer of [...observers]) {
      for (const target of [...observer.targets]) {
        observer.callback(
          [
            {
              target,
              isIntersecting: active,
              intersectionRatio: active ? 1 : 0,
              boundingClientRect: target.getBoundingClientRect(),
              intersectionRect: target.getBoundingClientRect(),
              rootBounds: null,
              time: 0,
            },
          ],
          {} as IntersectionObserver,
        );
      }
    }
  };
}

function renderPlayer(mediaId: string) {
  return render(
    <div data-media-scroll-root>
      <ConfiguredVideo
        mediaId={mediaId}
        src={`https://video.example/${mediaId}.mp4`}
        poster="https://video.example/poster.jpg"
        autoPlay={false}
        loop
        volume={35}
        translation={translate("ja")}
      />
    </div>,
  );
}

test("同時に再生されるのは常に1つ", async () => {
  const notify = installHarness();
  const first = renderPlayer("first");
  const second = renderPlayer("second");
  act(() => notify(true));
  const firstVideo = first.container.querySelector("video");
  const secondVideo = second.container.querySelector("video");
  if (firstVideo === null || secondVideo === null) throw new Error("Missing videos");

  await act(async () => {
    await firstVideo.play();
  });
  expect(firstVideo.paused).toBe(false);
  await act(async () => {
    await secondVideo.play();
  });
  expect(secondVideo.paused).toBe(false);
  expect(firstVideo.paused).toBe(true);
  first.unmount();
  second.unmount();
});

test("どこを触っても暫定ミュートが解除される", async () => {
  const notify = installHarness();
  const rendered = renderPlayer("tap");
  act(() => notify(true));
  const video = rendered.container.querySelector("video");
  if (video === null) throw new Error("Missing video");
  expect(video.muted).toBe(true);

  await act(async () => {
    await video.play();
  });
  fireEvent.click(video);
  expect(video.muted).toBe(false);
  // 解除のタップでは再生状態を変えない
  expect(video.paused).toBe(false);
  rendered.unmount();
});

test("全画面スタイルは画面全体を埋める", async () => {
  const { fullscreenPlayerStyle, fullscreenVideoStyle } = await import("./configured-video");
  expect(fullscreenPlayerStyle(false)).toBeUndefined();
  expect(fullscreenVideoStyle(false)).toBeUndefined();
  const player = fullscreenPlayerStyle(true);
  const video = fullscreenVideoStyle(true);
  if (player === undefined || video === undefined) throw new Error("Missing styles");
  expect(player.width).toBe("100vw");
  expect(player.height).toBe("100vh");
  expect(video.width).toBe("100%");
  expect(video.height).toBe("100%");
});

test("再生・一時停止・終了・画面外への移動で消灯防止を取得・解除する", async () => {
  const locks: Array<{ releases: number }> = [];
  Object.defineProperty(navigator, "wakeLock", {
    configurable: true,
    value: {
      request: async () => {
        const target = new EventTarget();
        const lock = Object.assign(target, {
          releases: 0,
          async release() {
            this.releases += 1;
            target.dispatchEvent(new Event("release"));
          },
        });
        locks.push(lock);
        return lock;
      },
    },
  });
  const notify = installHarness();
  const rendered = renderPlayer("wake");
  act(() => notify(true));
  const video = rendered.container.querySelector("video");
  if (video === null) throw new Error("Missing video");
  expect(locks).toHaveLength(0);
  await act(async () => {
    await video.play();
  });
  expect(locks).toHaveLength(1);
  await act(async () => {
    video.pause();
  });
  expect(locks[0]?.releases).toBe(1);
  await act(async () => {
    await video.play();
  });
  await act(async () => {
    fireEvent.ended(video);
  });
  expect(locks[1]?.releases).toBe(1);
  await act(async () => {
    await video.play();
  });
  await act(async () => {
    notify(false);
  });
  expect(locks[2]?.releases).toBe(1);
  expect(rendered.container.querySelector("video")).toBeNull();
  rendered.unmount();
});

test("再生映像の表示後はサムネイルを外し、一時停止でも戻さず再接続時は初期状態へ戻す", async () => {
  const notify = installHarness();
  const rendered = renderPlayer("poster");
  act(() => notify(true));
  const video = rendered.container.querySelector("video");
  if (video === null) throw new Error("Missing video");
  expect(video.getAttribute("poster")).toBe("https://video.example/poster.jpg");
  await act(async () => {
    await video.play();
  });
  fireEvent.playing(video);
  expect(video.hasAttribute("poster")).toBe(false);
  act(() => video.pause());
  expect(video.hasAttribute("poster")).toBe(false);
  act(() => notify(false));
  expect(rendered.container.querySelector(".configured-video-poster")).not.toBeNull();
  act(() => notify(true));
  expect(rendered.container.querySelector("video")?.getAttribute("poster")).toBe(
    "https://video.example/poster.jpg",
  );
  rendered.unmount();
});

test("消灯防止の許可が拒否されても動画を再生し続ける", async () => {
  const warning = spyOn(console, "warn").mockImplementation(() => {});
  try {
    Object.defineProperty(navigator, "wakeLock", {
      configurable: true,
      value: { request: () => Promise.reject(new DOMException("Denied", "NotAllowedError")) },
    });
    const notify = installHarness();
    const rendered = renderPlayer("denied");
    act(() => notify(true));
    const video = rendered.container.querySelector("video");
    if (video === null) throw new Error("Missing video");
    await act(async () => {
      await video.play();
    });
    expect(video.paused).toBe(false);
    expect(warning).toHaveBeenCalledTimes(1);
    rendered.unmount();
  } finally {
    warning.mockRestore();
  }
});
