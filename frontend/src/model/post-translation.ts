import type { Locale } from "./layout";
import { fetchWithTimeout } from "./fetch-with-timeout";
import { TranslationMemory, translationMemoryKey } from "./translation-memory";

export interface PostTranslationResult {
  postId: string;
  sourceLanguage: string;
  targetLanguage: string;
  text: string;
  provider: "X";
}

const unavailableLanguages = new Set(["und", "zxx", "qme", "qam", "art"]);
const memory = new TranslationMemory<PostTranslationResult>();

export function cachedPostTranslation(scope: {
  accountId: string;
  postId: string;
  sourceLanguage: string;
  targetLanguage: Locale;
  text?: string;
}): PostTranslationResult | undefined {
  return memory.get(translationMemoryKey({ ...scope, kind: "post", id: scope.postId }));
}
const queue: Array<() => void> = [];
let activeRequests = 0;
const maxConcurrentRequests = 2;
const maximumAttempts = 4;
const translationRequestTimeoutMilliseconds = 45_000;
const maximumRateLimitWaitMilliseconds = 60 * 60 * 1_000;
const retryDelaysMilliseconds = [750, 2_000, 5_000] as const;

export function hasTranslatableText(text: string): boolean {
  const prose = text
    .replace(/https?:\/\/\S+/giu, "")
    .replace(/(?<![\p{L}\p{N}_])[@＠][A-Za-z0-9_]{1,15}(?![A-Za-z0-9_])/gu, "");
  return /\p{L}/u.test(prose);
}

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
  text,
  onRetryScheduled,
}: {
  accountId: string;
  postId: string;
  sourceLanguage: string;
  targetLanguage: Locale;
  text?: string;
  onRetryScheduled?: (delaySeconds: number) => void;
}): Promise<PostTranslationResult> {
  const key = translationMemoryKey({
    accountId,
    kind: "post",
    id: postId,
    sourceLanguage,
    targetLanguage,
    text,
  });
  const params = new URLSearchParams({ accountId, sourceLanguage, targetLanguage });
  return memory.load(
    key,
    () =>
      requestTranslationWithRetry(
        `/api/v1/posts/${encodeURIComponent(postId)}/translation?${params}`,
        onRetryScheduled,
      ).then((result) => {
        if (!isPostTranslationResult(result)) throw new Error("Invalid X translation response");
        if (
          result.postId !== postId ||
          result.sourceLanguage.toLowerCase().replaceAll("_", "-") !==
            sourceLanguage.toLowerCase().replaceAll("_", "-") ||
          result.targetLanguage !== targetLanguage
        ) {
          throw new Error("Invalid X translation response scope");
        }
        return result;
      }),
    (result) => result.text.trim().length > 0,
  );
}

export async function requestTranslationWithRetry(
  uri: string,
  onRetryScheduled?: (delaySeconds: number) => void,
): Promise<unknown> {
  for (let attempt = 0; attempt < maximumAttempts; attempt += 1) {
    let response: Response;
    try {
      response = await scheduleTranslation(() =>
        fetchWithTimeout(uri, {}, translationRequestTimeoutMilliseconds),
      );
    } catch (error) {
      if (attempt >= maximumAttempts - 1) throw error;
      await waitBeforeRetry(retryDelaysMilliseconds[attempt] ?? 5_000, onRetryScheduled);
      continue;
    }
    if (response.ok) {
      return response.json();
    }
    if (!isRetryableStatus(response.status) || attempt >= maximumAttempts - 1) {
      throw new Error(`HTTP ${response.status}`);
    }
    const delay =
      response.status === 429
        ? retryAfterMilliseconds(response)
        : (retryDelaysMilliseconds[attempt] ?? 5_000);
    await waitBeforeRetry(delay, onRetryScheduled);
  }
  throw new Error("X translation retries exhausted");
}

function isRetryableStatus(status: number): boolean {
  return status === 408 || status === 425 || status === 429 || status >= 500;
}

function retryAfterMilliseconds(response: Response): number {
  const seconds = Number(response.headers.get("Retry-After"));
  if (!Number.isFinite(seconds) || seconds < 0) {
    return retryDelaysMilliseconds[0];
  }
  return Math.min(maximumRateLimitWaitMilliseconds, Math.ceil(seconds * 1_000));
}

async function waitBeforeRetry(
  delayMilliseconds: number,
  onRetryScheduled?: (delaySeconds: number) => void,
): Promise<void> {
  onRetryScheduled?.(Math.max(1, Math.ceil(delayMilliseconds / 1_000)));
  await new Promise<void>((resolve) => globalThis.setTimeout(resolve, delayMilliseconds));
  onRetryScheduled?.(0);
}

function isPostTranslationResult(value: unknown): value is PostTranslationResult {
  if (typeof value !== "object" || value === null) return false;
  const result = value as Record<string, unknown>;
  return (
    typeof result.postId === "string" &&
    typeof result.sourceLanguage === "string" &&
    typeof result.targetLanguage === "string" &&
    typeof result.text === "string" &&
    result.text.trim().length > 0 &&
    result.provider === "X"
  );
}

export function scheduleTranslation<T>(operation: () => Promise<T>): Promise<T> {
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
