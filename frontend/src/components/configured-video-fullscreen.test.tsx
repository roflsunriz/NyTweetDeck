import { afterEach, expect, test } from "bun:test";
import { act, cleanup, fireEvent, render } from "@testing-library/react";
import { translate } from "../i18n/translations";
import { ConfiguredVideo } from "./configured-video";

const originalObserver = globalThis.IntersectionObserver;
const fullscreenDescriptor = Object.getOwnPropertyDescriptor(document, "fullscreenElement");
const originalPlay = HTMLMediaElement.prototype.play;
const originalPause = HTMLMediaElement.prototype.pause;
const originalLoad = HTMLMediaElement.prototype.load;

afterEach(() => {
  cleanup();
  globalThis.IntersectionObserver = originalObserver;
  HTMLMediaElement.prototype.play = originalPlay;
  HTMLMediaElement.prototype.pause = originalPause;
  HTMLMediaElement.prototype.load = originalLoad;
  if (fullscreenDescriptor)
    Object.defineProperty(document, "fullscreenElement", fullscreenDescriptor);
  else Reflect.deleteProperty(document, "fullscreenElement");
});

for (const scenario of [
  { autoPlay: true, paused: false },
  { autoPlay: false, paused: false },
  { autoPlay: true, paused: true },
]) {
  test(`fullscreen preserves video/time with autoplay=${scenario.autoPlay} paused=${scenario.paused}`, async () => {
    let fullscreen: Element | null = null;
    Object.defineProperty(document, "fullscreenElement", {
      configurable: true,
      get: () => fullscreen,
    });
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
    const { container } = render(
      <div data-media-scroll-root>
        <ConfiguredVideo
          mediaId="full"
          src="https://video.example/clip.mp4"
          poster="https://video.example/poster.jpg"
          variants={[
            { url: "https://video.example/low.mp4", bitrate: 100 },
            { url: "https://video.example/clip.mp4", bitrate: 1000 },
          ]}
          autoPlay={scenario.autoPlay}
          loop
          volume={35}
          translation={translate("ja")}
        />
      </div>,
    );
    const player = container.querySelector("fieldset");
    if (player === null) throw new Error("Missing video player");
    const notify = (active: boolean) => {
      for (const observer of [...observers]) {
        if (observer.targets.has(player))
          observer.callback(
            [
              {
                target: player,
                isIntersecting: active,
                intersectionRatio: active ? 1 : 0,
                boundingClientRect: player.getBoundingClientRect(),
                intersectionRect: player.getBoundingClientRect(),
                rootBounds: null,
                time: 0,
              },
            ],
            {} as IntersectionObserver,
          );
      }
    };
    act(() => notify(true));
    let video = container.querySelector("video");
    if (video === null) throw new Error("Missing connected video");
    const initialVideo = video;
    await act(async () => {
      if (scenario.paused) initialVideo.pause();
      else {
        await initialVideo.play();
        fireEvent.playing(initialVideo);
      }
    });
    video.currentTime = 7;
    fireEvent.click(video);
    const volume = container.querySelector<HTMLInputElement>(".configured-video-volume");
    if (volume === null) throw new Error("Missing volume control");
    fireEvent.change(volume, { target: { value: "23" } });
    let finish: (() => void) | undefined;
    Object.defineProperty(player, "requestFullscreen", {
      configurable: true,
      value: () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    });
    const button = container.querySelector('[data-video-action="fullscreen"]');
    if (button === null) throw new Error("Missing fullscreen control");
    fireEvent.click(button);
    act(() => notify(false));
    expect(container.querySelector("video")).toBe(video);
    await act(async () => {
      fullscreen = player;
      document.dispatchEvent(new Event("fullscreenchange"));
      finish?.();
    });
    act(() => notify(false));
    expect(container.querySelector("video")).toBe(video);
    expect(video.getAttribute("src")).toBe("https://video.example/clip.mp4");
    expect(video.currentTime).toBe(7);
    expect(video.muted).toBe(false);
    expect(video.volume).toBe(0.23);
    expect(video.paused).toBe(scenario.paused);
    expect(video.hasAttribute("poster")).toBe(scenario.paused);
    const quality = container.querySelector<HTMLSelectElement>(".configured-video-quality");
    if (quality === null) throw new Error("Missing quality control");
    fireEvent.change(quality, { target: { value: "low" } });
    video = container.querySelector("video");
    if (video === null) throw new Error("Missing replacement video");
    const replacementVideo = video;
    Object.defineProperty(video, "duration", { configurable: true, value: 125 });
    await act(async () => fireEvent.loadedMetadata(replacementVideo));
    expect(video.currentTime).toBe(7);
    expect(video.paused).toBe(scenario.paused);
    expect(video.muted).toBe(false);
    expect(video.volume).toBe(0.23);
    expect(quality.value).toBe("low");
    act(() => {
      fullscreen = null;
      document.dispatchEvent(new Event("fullscreenchange"));
    });
    expect(container.querySelector("video")).toBe(video);
    act(() => notify(true));
    expect(container.querySelector("video")).toBe(video);
    expect(video.currentTime).toBe(7);
    expect(video.muted).toBe(false);
    expect(video.volume).toBe(0.23);
    expect(quality.value).toBe("low");
    expect(video.paused).toBe(scenario.paused);
    act(() => notify(false));
    expect(container.querySelector("video")).toBeNull();
  });
}
