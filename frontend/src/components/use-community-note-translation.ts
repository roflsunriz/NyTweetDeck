import { useEffect, useState } from "react";
import {
  hasTranslatableText,
  requestTranslationWithRetry,
  shouldTranslatePost,
} from "../model/post-translation";
import { TranslationMemory, translationMemoryKey } from "../model/translation-memory";
import type { CommunityNote } from "../model/timeline";
import { usePostTranslationSettings } from "./post-translation-context";

interface NoteTranslation {
  key: string;
  available: boolean;
  text: string | null;
  sources: NonNullable<CommunityNote["sources"]>;
}
const memory = new TranslationMemory<NoteTranslation>();

export function useCommunityNoteTranslation(
  note: CommunityNote,
  accountId: string,
  active: boolean,
) {
  const { translationLocale, autoTranslatePosts } = usePostTranslationSettings();
  const [result, setResult] = useState<(NoteTranslation & { attempt: number }) | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const [showOriginal, setShowOriginal] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const key = translationMemoryKey({
    accountId,
    kind: "note",
    id: note.noteId ?? "",
    sourceLanguage: note.language ?? null,
    targetLanguage: translationLocale,
    text: note.text ?? "",
  });
  const cached = memory.get(key);
  const needed =
    Boolean(note.noteId) &&
    hasTranslatableText(note.text ?? "") &&
    (shouldTranslatePost(note.language ?? null, translationLocale) ||
      (note.language == null && note.isTranslatable === true));
  useEffect(() => {
    let mounted = true;
    setResult(null);
    setError(false);
    setLoading(false);
    setShowOriginal(false);
    if (!needed || !autoTranslatePosts || !active || !note.noteId) return;
    if (cached !== undefined) return;
    const pending = memory.load(
      key,
      () => {
        const params = new URLSearchParams({ accountId, targetLanguage: translationLocale });
        return (async () => {
          const value = await requestTranslationWithRetry(
            `/api/v1/community-notes/${encodeURIComponent(note.noteId ?? "")}/translation?${params}`,
          );
          if (typeof value !== "object" || value === null)
            throw new Error("Invalid note translation");
          const data = value as Record<string, unknown>;
          if (
            data.noteId !== note.noteId ||
            data.targetLanguage !== translationLocale ||
            typeof data.available !== "boolean" ||
            !Array.isArray(data.sources) ||
            (data.available && (typeof data.text !== "string" || data.text.trim().length === 0))
          )
            throw new Error("Invalid note translation");
          const sources = data.sources.filter(
            (source): source is NonNullable<CommunityNote["sources"]>[number] =>
              typeof source === "object" &&
              source !== null &&
              typeof source.fromIndex === "number" &&
              typeof source.toIndex === "number" &&
              typeof source.url === "string" &&
              /^https?:\/\//i.test(source.url),
          );
          return {
            key,
            available: data.available,
            text: typeof data.text === "string" ? data.text : null,
            sources,
          };
        })();
      },
      (value) => value.available && Boolean(value.text?.trim()),
    );
    setLoading(cached === undefined);
    void pending
      .then((value) => {
        if (mounted) setResult({ ...value, attempt });
      })
      .catch(() => {
        if (mounted) setError(true);
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
    needed,
    note.noteId,
    translationLocale,
    key,
    cached,
  ]);
  const currentResult = needed
    ? (cached ?? (result?.key === key && result.attempt === attempt ? result : null))
    : null;
  const translated = autoTranslatePosts && currentResult?.available === true && !showOriginal;
  return {
    loading: loading && currentResult === null,
    error,
    unavailable: currentResult?.available === false,
    needed: needed && autoTranslatePosts,
    translatedText: currentResult?.available ? currentResult.text : null,
    showOriginal,
    visibleNote: translated
      ? { ...note, text: currentResult.text, sources: currentResult.sources }
      : note,
    retry: () => {
      setAttempt((value) => value + 1);
    },
    toggleOriginal: () => setShowOriginal((value) => !value),
  };
}
