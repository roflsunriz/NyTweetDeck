const attempted = new Map<string, number>();
const recentAttempts: number[] = [];

/** Use the browser's normal image cache. Never download an unbounded hover queue. */
export function prefetchImages(urls: string[], load: (url: string) => void = loadImage): void {
  if (document.visibilityState === "hidden") return;
  const now = Date.now();
  while (recentAttempts.length > 0 && (recentAttempts[0] ?? now) <= now - 60_000)
    recentAttempts.shift();
  for (const url of [...new Set(urls)].slice(0, 4)) {
    if (recentAttempts.length >= 8) break;
    if (!/^https?:\/\//iu.test(url) || now - (attempted.get(url) ?? -Infinity) < 120_000) continue;
    recentAttempts.push(now);
    attempted.delete(url);
    attempted.set(url, now);
    while (attempted.size > 128) {
      const oldest = attempted.keys().next().value;
      if (oldest === undefined) break;
      attempted.delete(oldest);
    }
    load(url);
  }
}
function loadImage(url: string): void {
  const image = new Image();
  image.src = url;
}
