import { useEffect, useState } from "react";
import {
  cachedPostTranslation,
  hasTranslatableText,
  loadPostTranslation,
  shouldTranslatePost,
  translationTargetsLocale,
} from "../model/post-translation";
import { translationMemoryKey } from "../model/translation-memory";
import type { PreTranslatedPost } from "../model/timeline";
import { usePostTranslationSettings } from "./post-translation-context";

export interface PostTranslationView {
  autoTranslatePosts: boolean;
  error: boolean;
  loading: boolean;
  needed: boolean;
  provider: string | null;
  retry: () => void;
  retrySeconds: number;
  showOriginal: boolean;
  toggleOriginal: () => void;
  translatedText: string | null;
  visibleText: string;
}

export function usePostTranslation({
  accountId,
  postId,
  text,
  language,
  preTranslated,
  active,
}: {
  accountId: string;
  postId: string;
  text: string;
  language: string | null;
  preTranslated?: PreTranslatedPost | null;
  active: boolean;
}): PostTranslationView {
  const [fetchedKey, setFetchedKey] = useState<string | null>(null);
  const [fetchedText, setFetchedText] = useState<string | null>(null);
  const [fetchedProvider, setFetchedProvider] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const [retrySeconds, setRetrySeconds] = useState(0);
  const [showOriginal, setShowOriginal] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const { translationLocale, autoTranslatePosts } = usePostTranslationSettings();
  const availablePreTranslation =
    preTranslated !== undefined &&
    preTranslated !== null &&
    translationTargetsLocale(preTranslated.targetLanguage, translationLocale)
      ? preTranslated
      : null;
  const needed =
    hasTranslatableText(text) &&
    (availablePreTranslation !== null || shouldTranslatePost(language, translationLocale));
  const key = translationMemoryKey({
    accountId,
    kind: "post",
    id: postId,
    sourceLanguage: language,
    targetLanguage: translationLocale,
    text,
  });
  const cached =
    language === null
      ? undefined
      : cachedPostTranslation({
          accountId,
          postId,
          sourceLanguage: language,
          targetLanguage: translationLocale,
          text,
        });
  const translatedText = needed
    ? (availablePreTranslation?.text ??
      cached?.text ??
      (fetchedKey === `${key}:${attempt}` ? fetchedText : null))
    : null;
  const provider =
    availablePreTranslation?.provider ??
    cached?.provider ??
    (fetchedKey === `${key}:${attempt}` ? fetchedProvider : null);
  const visibleText =
    autoTranslatePosts && translatedText !== null && !showOriginal ? translatedText : text;

  useEffect(() => {
    if (!autoTranslatePosts || !needed || !active) {
      setLoading(false);
      setError(false);
      setRetrySeconds(0);
      setShowOriginal(false);
      return;
    }
    if (availablePreTranslation !== null) {
      setFetchedText(null);
      setFetchedProvider(null);
      setLoading(false);
      setError(false);
      setRetrySeconds(0);
      setShowOriginal(false);
      return;
    }
    if (language === null) {
      setLoading(false);
      setError(false);
      setRetrySeconds(0);
      setShowOriginal(false);
      return;
    }
    if (cached !== undefined) {
      setLoading(false);
      setError(false);
      setRetrySeconds(0);
      return;
    }
    let mounted = true;
    setFetchedText(null);
    setFetchedProvider(null);
    setLoading(cached === undefined);
    setError(false);
    setRetrySeconds(0);
    setShowOriginal(false);
    void loadPostTranslation({
      accountId,
      postId,
      sourceLanguage: language,
      targetLanguage: translationLocale,
      text,
      onRetryScheduled: (delaySeconds) => {
        if (mounted) setRetrySeconds(delaySeconds);
      },
    })
      .then((result) => {
        if (!mounted) return;
        setFetchedKey(`${key}:${attempt}`);
        setFetchedText(result.text);
        setFetchedProvider(result.provider);
        setRetrySeconds(0);
      })
      .catch(() => {
        if (mounted) {
          setRetrySeconds(0);
          setError(true);
        }
      })
      .finally(() => {
        if (mounted) setLoading(false);
      });
    return () => {
      mounted = false;
    };
  }, [
    accountId,
    active,
    attempt,
    autoTranslatePosts,
    availablePreTranslation,
    language,
    translationLocale,
    needed,
    postId,
    text,
    cached,
    key,
  ]);

  return {
    autoTranslatePosts,
    error,
    loading: loading && translatedText === null,
    needed,
    provider,
    retry: () => setAttempt((value) => value + 1),
    retrySeconds,
    showOriginal,
    toggleOriginal: () => setShowOriginal((value) => !value),
    translatedText,
    visibleText,
  };
}
