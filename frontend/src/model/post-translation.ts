import type { Locale } from "./layout";

export interface PostTranslationResult {
  postId: string;
  sourceLanguage: string;
  targetLanguage: string;
  text: string;
  provider: string;
}

const unavailableLanguages = new Set(["und", "zxx", "qme", "qam", "art"]);
const requests = new Map<string, Promise<PostTranslationResult>>();
const queue: Array<() => void> = [];
let activeRequests = 0;
const maxConcurrentRequests = 4;

export function shouldTranslatePost(sourceLanguage: string | null, locale: Locale): boolean {
  const source = normalizeBaseLanguage(sourceLanguage);
  const target = normalizeBaseLanguage(locale);
  return (
    source !== null && target !== null && !unavailableLanguages.has(source) && source !== target
  );
}

export function loadPostTranslation({
  accountId,
  postId,
  sourceLanguage,
  targetLanguage,
  force = false,
}: {
  accountId: string;
  postId: string;
  sourceLanguage: string;
  targetLanguage: Locale;
  force?: boolean;
}): Promise<PostTranslationResult> {
  const key = `${accountId}:${postId}:${targetLanguage}`;
  if (force) requests.delete(key);
  const existing = requests.get(key);
  if (existing !== undefined) return existing;
  const params = new URLSearchParams({ accountId, sourceLanguage, targetLanguage });
  const request = schedule(() =>
    fetch(`/api/v1/posts/${encodeURIComponent(postId)}/translation?${params}`).then(
      async (response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return (await response.json()) as PostTranslationResult;
      },
    ),
  ).catch((error: unknown) => {
    requests.delete(key);
    throw error;
  });
  requests.set(key, request);
  if (requests.size > 500) {
    const oldest = requests.keys().next().value;
    if (oldest !== undefined && oldest !== key) requests.delete(oldest);
  }
  return request;
}

function schedule<T>(operation: () => Promise<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    queue.push(() => {
      activeRequests += 1;
      void operation()
        .then(resolve, reject)
        .finally(() => {
          activeRequests -= 1;
          runQueuedRequests();
        });
    });
    runQueuedRequests();
  });
}

function runQueuedRequests() {
  while (activeRequests < maxConcurrentRequests) {
    const next = queue.shift();
    if (next === undefined) return;
    next();
  }
}

function normalizeBaseLanguage(language: string | null): string | null {
  if (language === null) return null;
  const normalized = language.trim().toLowerCase().replace("_", "-");
  if (!/^[a-z]{2,3}(?:-[a-z0-9]{2,8})*$/.test(normalized)) return null;
  return normalized.split("-", 1)[0] ?? null;
}
