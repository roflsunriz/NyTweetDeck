import { useEffect, useState } from "react";
import { fetchWithTimeout } from "../model/fetch-with-timeout";
import {
  hasTranslatableText,
  scheduleTranslation,
  shouldTranslatePost,
} from "../model/post-translation";
import type { CommunityNote } from "../model/timeline";
import { usePostTranslationSettings } from "./post-translation-context";

interface NoteTranslation {
  key: string;
  available: boolean;
  text: string | null;
  sources: NonNullable<CommunityNote["sources"]>;
}
const requests = new Map<string, Promise<NoteTranslation>>();

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
  const key = `${accountId}:${note.noteId}:${translationLocale}`;
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
    let pending = requests.get(key);
    if (!pending) {
      const params = new URLSearchParams({ accountId, targetLanguage: translationLocale });
      pending = scheduleTranslation(async () => {
        const response = await fetchWithTimeout(
          `/api/v1/community-notes/${encodeURIComponent(note.noteId ?? "")}/translation?${params}`,
          {},
          45_000,
        );
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const value: unknown = await response.json();
        if (typeof value !== "object" || value === null)
          throw new Error("Invalid note translation");
        const data = value as Record<string, unknown>;
        if (
          data.noteId !== note.noteId ||
          data.targetLanguage !== translationLocale ||
          typeof data.available !== "boolean" ||
          !Array.isArray(data.sources) ||
          (data.available && typeof data.text !== "string")
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
      }).catch((failure: unknown) => {
        requests.delete(key);
        throw failure;
      });
      requests.set(key, pending);
      if (requests.size > 500) {
        const oldest = requests.keys().next().value;
        if (oldest !== undefined) requests.delete(oldest);
      }
    }
    setLoading(true);
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
  }, [accountId, active, attempt, autoTranslatePosts, needed, note.noteId, translationLocale, key]);
  const currentResult = result?.key === key && result.attempt === attempt && needed ? result : null;
  const translated = autoTranslatePosts && currentResult?.available === true && !showOriginal;
  return {
    loading,
    error,
    unavailable: currentResult?.available === false,
    needed: needed && autoTranslatePosts,
    translatedText: currentResult?.available ? currentResult.text : null,
    showOriginal,
    visibleNote: translated
      ? { ...note, text: currentResult.text, sources: currentResult.sources }
      : note,
    retry: () => {
      requests.delete(key);
      setAttempt((value) => value + 1);
    },
    toggleOriginal: () => setShowOriginal((value) => !value),
  };
}
