import type { TimelinePost } from "./post-card";

export interface RecordedIntersectionObserver {
  callback: IntersectionObserverCallback;
  options: IntersectionObserverInit;
  targets: Element[];
  notify(target: Element, isIntersecting: boolean): void;
}

export function installIntersectionObserverMock(): RecordedIntersectionObserver[] {
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

export function restoreProperty(
  target: object,
  key: PropertyKey,
  descriptor: PropertyDescriptor | undefined,
) {
  if (descriptor === undefined) Reflect.deleteProperty(target, key);
  else Object.defineProperty(target, key, descriptor);
}

export function post(): TimelinePost {
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
