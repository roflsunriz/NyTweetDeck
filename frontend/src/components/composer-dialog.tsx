import { type FormEvent, useState } from "react";
import type { Translation } from "../i18n/translations";
import { Modal } from "./modal";

interface ComposerDialogProps {
  translation: Translation;
  accountId: string | null;
  inReplyToPostId?: string;
  quotePostId?: string;
  quotePostUrl?: string;
  onClose: () => void;
  onPublished?: () => void;
}

export function ComposerDialog({
  translation,
  accountId,
  inReplyToPostId,
  quotePostId,
  quotePostUrl,
  onClose,
  onPublished,
}: ComposerDialogProps) {
  const [text, setText] = useState("");
  const [publishing, setPublishing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (accountId === null || text.trim().length === 0) {
      return;
    }
    setPublishing(true);
    setError(null);
    try {
      const response = await fetch("/api/v1/posts", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ accountId, text: text.trim(), inReplyToPostId, quotePostId }),
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      setText("");
      onPublished?.();
      onClose();
    } catch {
      setError(translation.postFailed);
    } finally {
      setPublishing(false);
    }
  };

  return (
    <Modal
      title={
        inReplyToPostId !== undefined
          ? translation.reply
          : quotePostId !== undefined
            ? translation.quote
            : translation.composeTitle
      }
      closeLabel={translation.close}
      onClose={onClose}
    >
      {accountId === null ? (
        <p className="composer-message">{translation.noAccounts}</p>
      ) : (
        <form className="composer-form" onSubmit={submit}>
          {quotePostUrl !== undefined && (
            <a className="quote-preview-link" href={quotePostUrl} target="_blank" rel="noreferrer">
              {translation.quotingPost}
            </a>
          )}
          <textarea
            required
            maxLength={4000}
            placeholder={translation.postPlaceholder}
            value={text}
            onChange={(event) => setText(event.target.value)}
          />
          <div className="composer-footer">
            <span>{text.length}/4000</span>
            <button
              className="primary-button"
              type="submit"
              disabled={publishing || text.trim().length === 0}
            >
              {publishing ? translation.publishing : translation.publishPost}
            </button>
          </div>
          {error !== null && <p className="setup-error">{error}</p>}
        </form>
      )}
    </Modal>
  );
}
