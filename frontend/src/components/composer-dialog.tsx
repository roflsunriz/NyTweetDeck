import type { Translation } from "../i18n/translations";
import { useMediaQuery } from "../model/use-media-query";
import { useOverlayRoute } from "../model/use-overlay-route";
import { ComposerForm } from "./composer-form";
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
  const compactPresentation = useMediaQuery("(max-width: 599px)");
  const close = useOverlayRoute("compose", onClose);

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
      onClose={close}
      presentation={compactPresentation ? "full-page" : "modal"}
    >
      {accountId === null ? (
        <p className="composer-message">{translation.noAccounts}</p>
      ) : (
        <ComposerForm
          translation={translation}
          accountId={accountId}
          inReplyToPostId={inReplyToPostId}
          quotePostId={quotePostId}
          quotePostUrl={quotePostUrl}
          onClose={close}
          onPublished={onPublished}
        />
      )}
    </Modal>
  );
}
