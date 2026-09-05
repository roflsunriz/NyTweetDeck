import { useCallback, useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import type { DisplayPreferences, Locale } from "../model/layout";
import { Modal } from "./modal";
import { PostCard, type TimelinePost } from "./post-card";

interface CommunityNoteDetail {
  noteId: string;
  text: string;
  language: string | null;
  isTranslatable: boolean | null;
  sources: Array<{ fromIndex: number; toIndex: number; url: string }>;
  post: TimelinePost;
}

export function CommunityNoteDetailDialog({
  noteId,
  accountId,
  locale,
  translation,
  display,
  onClose,
  onOpenPost,
  onOpenUser,
}: {
  noteId: string;
  accountId: string;
  locale: Locale;
  translation: Translation;
  display: DisplayPreferences;
  onClose: () => void;
  onOpenPost?: (postId: string) => void;
  onOpenUser?: (userId: string) => void;
}) {
  const [detail, setDetail] = useState<CommunityNoteDetail | null>(null);
  const [error, setError] = useState(false);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      setDetail(null);
      setError(false);
      const params = new URLSearchParams({ accountId, language: locale });
      try {
        const response = await fetch(
          `/api/v1/community-notes/${encodeURIComponent(noteId)}?${params}`,
          { signal },
        );
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        setDetail((await response.json()) as CommunityNoteDetail);
      } catch (requestError) {
        if (!(requestError instanceof DOMException && requestError.name === "AbortError")) {
          setError(true);
        }
      }
    },
    [accountId, locale, noteId],
  );

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  return (
    <Modal
      title={translation.communityNoteDetails}
      closeLabel={translation.closeDetail}
      onClose={onClose}
    >
      <div className="post-detail-content community-note-post-detail">
        {error ? (
          <div className="column-message">
            <strong>{translation.communityNoteLoadError}</strong>
            <button className="secondary-button" type="button" onClick={() => void load()}>
              {translation.retry}
            </button>
          </div>
        ) : detail === null ? (
          <p>{translation.loading}</p>
        ) : (
          <PostCard
            post={{
              ...detail.post,
              communityNote: {
                noteId: detail.noteId,
                language: detail.language,
                isTranslatable: detail.isTranslatable,
                title: translation.communityNote,
                text: detail.text,
                footer: null,
                sources: detail.sources,
              },
            }}
            accountId={accountId}
            translation={translation}
            display={display}
            onOpenQuotedPost={onOpenPost}
            onOpenUser={onOpenUser}
          />
        )}
      </div>
    </Modal>
  );
}
