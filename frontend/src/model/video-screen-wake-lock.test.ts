import { afterEach, expect, test } from "bun:test";
import { setVideoScreenWakeLock } from "./video-screen-wake-lock";

const wakeLockDescriptor = Object.getOwnPropertyDescriptor(navigator, "wakeLock");
const visibilityDescriptor = Object.getOwnPropertyDescriptor(document, "visibilityState");
const videos = new Set<HTMLVideoElement>();

afterEach(async () => {
  for (const video of videos) setVideoScreenWakeLock(video, false);
  videos.clear();
  await flush();
  if (wakeLockDescriptor) Object.defineProperty(navigator, "wakeLock", wakeLockDescriptor);
  else Reflect.deleteProperty(navigator, "wakeLock");
  if (visibilityDescriptor)
    Object.defineProperty(document, "visibilityState", visibilityDescriptor);
  else Reflect.deleteProperty(document, "visibilityState");
});

function video(): HTMLVideoElement {
  const element = document.createElement("video");
  videos.add(element);
  return element;
}

function setVisible(visible: boolean): void {
  Object.defineProperty(document, "visibilityState", {
    configurable: true,
    value: visible ? "visible" : "hidden",
  });
  document.dispatchEvent(new Event("visibilitychange"));
}

function installWakeLock() {
  const locks: Array<
    EventTarget & { released: boolean; releases: number; release(): Promise<void> }
  > = [];
  let requests = 0;
  Object.defineProperty(navigator, "wakeLock", {
    configurable: true,
    value: {
      request: async (type: string) => {
        expect(type).toBe("screen");
        requests += 1;
        const target = new EventTarget();
        const lock = Object.assign(target, {
          released: false,
          releases: 0,
          async release() {
            this.released = true;
            this.releases += 1;
            target.dispatchEvent(new Event("release"));
          },
        });
        locks.push(lock);
        return lock;
      },
    },
  });
  setVisible(true);
  return { locks, requests: () => requests };
}

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

test("再生中だけ保持し、複数動画の片方が止まっても他方のロックを解除しない", async () => {
  const harness = installWakeLock();
  const first = video();
  const second = video();
  setVideoScreenWakeLock(first, true);
  setVideoScreenWakeLock(second, true);
  await flush();
  expect(harness.requests()).toBe(1);
  setVideoScreenWakeLock(first, false);
  expect(harness.locks[0]?.releases).toBe(0);
  setVideoScreenWakeLock(second, false);
  await flush();
  expect(harness.locks[0]?.releases).toBe(1);
});

test("非表示では解除し、再生を継続している場合だけ表示復帰時に再取得する", async () => {
  const harness = installWakeLock();
  const element = video();
  setVideoScreenWakeLock(element, true);
  await flush();
  setVisible(false);
  await flush();
  expect(harness.locks[0]?.released).toBe(true);
  setVisible(true);
  await flush();
  expect(harness.requests()).toBe(2);
  setVisible(false);
  setVideoScreenWakeLock(element, false);
  setVisible(true);
  await flush();
  expect(harness.requests()).toBe(2);
});

test("取得中に再生が止まった場合は遅れて届いたロックを即解除する", async () => {
  let resolve: ((value: WakeLockSentinel) => void) | undefined;
  const target = new EventTarget();
  const lock = Object.assign(target, {
    released: false,
    async release() {
      this.released = true;
      target.dispatchEvent(new Event("release"));
    },
  });
  Object.defineProperty(navigator, "wakeLock", {
    configurable: true,
    value: {
      request: () =>
        new Promise<WakeLockSentinel>((done) => {
          resolve = done;
        }),
    },
  });
  setVisible(true);
  const element = video();
  setVideoScreenWakeLock(element, true);
  setVideoScreenWakeLock(element, false);
  resolve?.(lock as unknown as WakeLockSentinel);
  await flush();
  expect(lock.released).toBe(true);
});

test("OSによる解除は無限に再要求せず、次の表示復帰で再要求する", async () => {
  const harness = installWakeLock();
  setVideoScreenWakeLock(video(), true);
  await flush();
  await harness.locks[0]?.release();
  await flush();
  expect(harness.requests()).toBe(1);
  setVisible(false);
  setVisible(true);
  await flush();
  expect(harness.requests()).toBe(2);
});

test("未使用のロックを解放する途中で再生が再開した場合も消灯防止を再取得する", async () => {
  let resolveRequest: ((value: WakeLockSentinel) => void) | undefined;
  let resolveRelease: (() => void) | undefined;
  let requests = 0;
  const target = new EventTarget();
  const firstLock = Object.assign(target, {
    release: () =>
      new Promise<void>((done) => {
        resolveRelease = done;
      }),
  });
  const replacement = Object.assign(new EventTarget(), { release: async () => {} });
  Object.defineProperty(navigator, "wakeLock", {
    configurable: true,
    value: {
      request: () => {
        requests += 1;
        return requests === 1
          ? new Promise<WakeLockSentinel>((done) => {
              resolveRequest = done;
            })
          : Promise.resolve(replacement);
      },
    },
  });
  setVisible(true);
  const element = video();
  setVideoScreenWakeLock(element, true);
  setVideoScreenWakeLock(element, false);
  resolveRequest?.(firstLock as unknown as WakeLockSentinel);
  await flush();
  setVideoScreenWakeLock(element, true);
  expect(requests).toBe(1);
  resolveRelease?.();
  await flush();
  expect(requests).toBe(2);
});

test("未対応APIでも再生状態の更新を妨げない", async () => {
  Object.defineProperty(navigator, "wakeLock", { configurable: true, value: undefined });
  Reflect.deleteProperty(navigator, "wakeLock");
  const element = video();
  setVideoScreenWakeLock(element, true);
  setVideoScreenWakeLock(element, false);
  await flush();
});
