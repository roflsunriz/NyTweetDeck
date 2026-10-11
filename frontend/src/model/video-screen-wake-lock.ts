const playingVideos = new Set<HTMLVideoElement>();
let sentinel: WakeLockSentinel | null = null;
let pending = false;
let listening = false;

/** 表示中に再生する動画がある間だけ、画面の自動消灯を防ぐ。 */
export function setVideoScreenWakeLock(video: HTMLVideoElement, playing: boolean): void {
  if (playing) playingVideos.add(video);
  else playingVideos.delete(video);
  if (playingVideos.size > 0 && !listening) {
    document.addEventListener("visibilitychange", synchronizeWakeLock);
    listening = true;
  } else if (playingVideos.size === 0 && listening) {
    document.removeEventListener("visibilitychange", synchronizeWakeLock);
    listening = false;
  }
  synchronizeWakeLock();
}

function shouldHoldWakeLock(): boolean {
  return playingVideos.size > 0 && document.visibilityState === "visible";
}

function synchronizeWakeLock(): void {
  if (!shouldHoldWakeLock()) {
    if (sentinel !== null) {
      const previous = sentinel;
      sentinel = null;
      void releaseWakeLock(previous);
    }
    return;
  }
  if (sentinel !== null || pending || !("wakeLock" in navigator)) return;
  pending = true;
  void requestWakeLock();
}

async function requestWakeLock(): Promise<void> {
  let releasedBeforeUse = false;
  try {
    const acquired = await navigator.wakeLock.request("screen");
    if (!shouldHoldWakeLock()) {
      releasedBeforeUse = true;
      await releaseWakeLock(acquired);
      return;
    }
    sentinel = acquired;
    acquired.addEventListener("release", () => {
      if (sentinel === acquired) sentinel = null;
      // 省電力などOS側の解除を強制的な再取得で妨げない。表示復帰や次の再生で再要求する。
    });
  } catch (error: unknown) {
    reportWakeLockFailure(error);
  } finally {
    pending = false;
    if (releasedBeforeUse && shouldHoldWakeLock()) synchronizeWakeLock();
  }
}

async function releaseWakeLock(lock: WakeLockSentinel): Promise<void> {
  try {
    await lock.release();
  } catch (error: unknown) {
    reportWakeLockFailure(error);
  }
}

function reportWakeLockFailure(error: unknown): void {
  // 未対応・省電力・ユーザーの権限設定が動画の再生を止めないよう、独立した処理にする。
  console.warn(
    "[NyTweetDeck] Screen wake lock unavailable",
    error instanceof Error ? error.name : "UnknownError",
  );
}
