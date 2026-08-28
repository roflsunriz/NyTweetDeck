import { createContext, type ReactNode, useContext, useRef } from "react";
import type { ColumnConfig, Locale } from "./layout";
import type { TimelinePage, TimelinePost } from "./timeline";

const maximumTimelineSnapshots = 64;
const maximumPostsPerSnapshot = 200;

interface CachedTimeline {
  page: TimelinePage;
  firstPage: TimelinePage;
  firstPageSignature: string;
}

export class TimelineMemoryCache {
  private readonly entries = new Map<string, CachedTimeline>();

  read(key: string): TimelinePage | null {
    const cached = this.entries.get(key);
    return cached?.page ?? null;
  }

  writeFirstPage(key: string, page: TimelinePage): boolean {
    const firstPage = copyPage(page);
    const firstPageSignature = JSON.stringify(firstPage);
    if (this.entries.get(key)?.firstPageSignature === firstPageSignature) return false;
    this.entries.delete(key);
    this.entries.set(key, { page: firstPage, firstPage, firstPageSignature });
    while (this.entries.size > maximumTimelineSnapshots) {
      const oldestKey = this.entries.keys().next().value;
      if (typeof oldestKey !== "string") break;
      this.entries.delete(oldestKey);
    }
    return true;
  }

  appendPage(key: string, page: TimelinePage): void {
    const cached = this.entries.get(key);
    if (cached === undefined) return;
    const appendedPosts = page.posts.filter(
      (post) => !cached.page.posts.some((item) => item.id === post.id),
    );
    if (cached.page.posts.length + appendedPosts.length > maximumPostsPerSnapshot) return;
    const posts = [...cached.page.posts, ...appendedPosts];
    this.entries.set(key, { ...cached, page: { posts, nextCursor: page.nextCursor } });
  }

  updatePosts(key: string, update: (posts: TimelinePost[]) => TimelinePost[]): void {
    const cached = this.entries.get(key);
    if (cached === undefined) return;
    const firstPage = { ...cached.firstPage, posts: update(cached.firstPage.posts) };
    this.entries.set(key, {
      page: { ...cached.page, posts: update(cached.page.posts) },
      firstPage,
      firstPageSignature: JSON.stringify(firstPage),
    });
  }
}

function copyPage(page: TimelinePage): TimelinePage {
  return {
    posts: page.posts.slice(0, maximumPostsPerSnapshot),
    nextCursor: page.nextCursor,
  };
}

const TimelineCacheContext = createContext<TimelineMemoryCache | null>(null);

export function TimelineCacheProvider({ children }: { children: ReactNode }) {
  const cacheRef = useRef<TimelineMemoryCache | null>(null);
  if (cacheRef.current === null) cacheRef.current = new TimelineMemoryCache();
  return (
    <TimelineCacheContext.Provider value={cacheRef.current}>
      {children}
    </TimelineCacheContext.Provider>
  );
}

export function useTimelineCache(): TimelineMemoryCache {
  const shared = useContext(TimelineCacheContext);
  const fallbackRef = useRef<TimelineMemoryCache | null>(null);
  if (fallbackRef.current === null) fallbackRef.current = new TimelineMemoryCache();
  return shared ?? fallbackRef.current;
}

export function createTimelineCacheKey(
  accountId: string,
  column: ColumnConfig,
  locale: Locale,
): string {
  return JSON.stringify([accountId, column.kind, column.target, locale]);
}
