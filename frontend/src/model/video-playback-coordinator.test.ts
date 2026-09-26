import { describe, expect, test } from "bun:test";
import {
  claimVideoPlayback,
  currentVideoPlayback,
  releaseVideoPlayback,
  resetVideoPlaybackForTesting,
  subscribeVideoPlayback,
} from "./video-playback-coordinator";

describe("video-playback-coordinator", () => {
  test("最後に再生を奪った1つだけが有効になる", () => {
    resetVideoPlaybackForTesting();
    claimVideoPlayback("a");
    expect(currentVideoPlayback()).toBe("a");
    claimVideoPlayback("b");
    expect(currentVideoPlayback()).toBe("b");
  });

  test("購読者へ切り替えが通知される", () => {
    resetVideoPlaybackForTesting();
    const seen: (string | null)[] = [];
    const unsubscribe = subscribeVideoPlayback((id) => seen.push(id));
    claimVideoPlayback("a");
    claimVideoPlayback("b");
    unsubscribe();
    claimVideoPlayback("c");
    expect(seen).toEqual(["a", "b"]);
    expect(currentVideoPlayback()).toBe("c");
  });

  test("保持していない解放は無視される", () => {
    resetVideoPlaybackForTesting();
    claimVideoPlayback("a");
    releaseVideoPlayback("b");
    expect(currentVideoPlayback()).toBe("a");
    releaseVideoPlayback("a");
    expect(currentVideoPlayback()).toBeNull();
  });
});
