import type { Locale } from "./layout";
import { fetchWithTimeout } from "./fetch-with-timeout";

export interface PostTranslationResult {
  postId: string;
  sourceLanguage: string;
  targetLanguage: string;
  text: string;
  provider: "X";
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

export function translationTargetsLocale(
  targetLanguage: string | null | undefined,
  locale: Locale,
): boolean {
  return normalizeBaseLanguage(targetLanguage ?? null) === normalizeBaseLanguage(locale);
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
    fetchWithTimeout(`/api/v1/posts/${encodeURIComponent(postId)}/translation?${params}`).then(
      async (response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const result: unknown = await response.json();
        if (!isPostTranslationResult(result)) {
          throw new Error("Invalid X translation response");
        }
        return result;
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

function isPostTranslationResult(value: unknown): value is PostTranslationResult {
  if (typeof value !== "object" || value === null) return false;
  const result = value as Record<string, unknown>;
  return (
    typeof result.postId === "string" &&
    typeof result.sourceLanguage === "string" &&
    typeof result.targetLanguage === "string" &&
    typeof result.text === "string" &&
    result.provider === "X"
  );
}

function schedule<T>(operation: () => Promise<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    queue.push(() => {
      activeRequests += 1;
      void Promise.resolve()
        .then(operation)
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
