import type { Translation } from "../i18n/translations";
import type { CommunityNote } from "../model/timeline";
import { useCommunityNoteTranslation } from "./use-community-note-translation";

export function CommunityNoteCard({
  note,
  accountId,
  active,
  translation,
}: {
  note: CommunityNote;
  accountId: string;
  active: boolean;
  translation: Translation;
}) {
  const state = useCommunityNoteTranslation(note, accountId, active);
  return (
    <aside
      className="community-note-card"
      data-testid="community-note-card"
      data-community-note-id={note.noteId}
    >
      <strong>{note.title || translation.communityNote}</strong>
      {note.text !== null && <p>{renderCommunityNoteText(state.visibleNote)}</p>}
      {state.needed && (
        <div className="post-translation-status" aria-live="polite">
          {state.loading && <span>{translation.translationLoading}</span>}
          {(state.error || state.unavailable) && (
            <>
              <span>
                {state.unavailable
                  ? translation.translationUnavailable
                  : translation.translationFailed}
              </span>
              <button type="button" onClick={state.retry}>
                {translation.retry}
              </button>
            </>
          )}
          {state.translatedText !== null && (
            <>
              <span>{translation.translatedBy("X")}</span>
              <button type="button" onClick={state.toggleOriginal}>
                {state.showOriginal ? translation.showTranslation : translation.showOriginal}
              </button>
            </>
          )}
        </div>
      )}
      {note.footer !== null && <small>{note.footer}</small>}
    </aside>
  );
}
function renderCommunityNoteText(note: CommunityNote) {
  const text = note.text ?? "";
  const sources = [...(note.sources ?? [])]
    .filter(
      (source) =>
        source.fromIndex >= 0 && source.toIndex > source.fromIndex && source.toIndex <= text.length,
    )
    .sort((first, second) => first.fromIndex - second.fromIndex);
  if (sources.length === 0) return text;
  const content: Array<string | ReturnType<typeof createCommunityNoteLink>> = [];
  let cursor = 0;
  for (const source of sources) {
    if (source.fromIndex < cursor) continue;
    if (source.fromIndex > cursor) content.push(text.slice(cursor, source.fromIndex));
    content.push(createCommunityNoteLink(source, text));
    cursor = source.toIndex;
  }
  if (cursor < text.length) content.push(text.slice(cursor));
  return content;
}

function createCommunityNoteLink(
  source: { fromIndex: number; toIndex: number; url: string },
  text: string,
) {
  return (
    <a
      key={`${source.fromIndex}:${source.toIndex}:${source.url}`}
      href={source.url}
      target="_blank"
      rel="noreferrer"
    >
      {text.slice(source.fromIndex, source.toIndex)}
    </a>
  );
}
