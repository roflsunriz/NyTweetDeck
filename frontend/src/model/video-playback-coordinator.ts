type PlaybackListener = (activeId: string | null) => void;

const listeners = new Set<PlaybackListener>();
let activeId: string | null = null;

/** 再生中の動画。複数あっても実際に音と映像を出すのは常に1つにする。 */
export function currentVideoPlayback(): string | null {
  return activeId;
}

export function claimVideoPlayback(id: string): void {
  if (activeId === id) return;
  activeId = id;
  listeners.forEach((listener) => {
    listener(activeId);
  });
}

export function releaseVideoPlayback(id: string): void {
  if (activeId !== id) return;
  activeId = null;
  listeners.forEach((listener) => {
    listener(activeId);
  });
}

export function subscribeVideoPlayback(listener: PlaybackListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** テスト用の初期化。製品コードからは呼ばない。 */
export function resetVideoPlaybackForTesting(): void {
  activeId = null;
  listeners.clear();
}
