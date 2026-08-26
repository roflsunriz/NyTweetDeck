import { useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import { ComposerDialog } from "./composer-dialog";
import { Modal } from "./modal";
import { PostCard, type TimelinePost } from "./post-card";

interface PostDetail {
  post: TimelinePost;
  replies: TimelinePost[];
  nextCursor: string | null;
}

interface PostDetailDialogProps {
  postId: string;
  accountId: string;
  translation: Translation;
  onClose: () => void;
}

export function PostDetailDialog({
  postId,
  accountId,
  translation,
  onClose,
}: PostDetailDialogProps) {
  const [detail, setDetail] = useState<PostDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [replying, setReplying] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    void fetch(`/api/v1/posts/${postId}?accountId=${encodeURIComponent(accountId)}`, {
      signal: controller.signal,
    })
      .then((response) => {
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        return response.json() as Promise<PostDetail>;
      })
      .then(setDetail)
      .catch((loadError) => {
        if (!(loadError instanceof DOMException && loadError.name === "AbortError")) {
          setError(translation.timelineLoadError);
        }
      });
    return () => controller.abort();
  }, [accountId, postId, translation.timelineLoadError]);

  return (
    <>
      <Modal title={translation.postDetail} closeLabel={translation.closeDetail} onClose={onClose}>
        <div className="post-detail-content">
          {error !== null ? (
            <p className="setup-error">{error}</p>
          ) : detail === null ? (
            <p>{translation.loading}</p>
          ) : (
            <>
              <PostCard post={detail.post} accountId={accountId} translation={translation} />
              <button
                className="primary-button detail-reply-button"
                type="button"
                onClick={() => setReplying(true)}
              >
                {translation.reply}
              </button>
              <h3>{translation.replies}</h3>
              {detail.replies.length === 0 ? (
                <p>{translation.noPosts}</p>
              ) : (
                detail.replies.map((reply) => (
                  <PostCard
                    key={reply.id}
                    post={reply}
                    accountId={accountId}
                    translation={translation}
                  />
                ))
              )}
            </>
          )}
        </div>
      </Modal>
      {replying && (
        <ComposerDialog
          translation={translation}
          accountId={accountId}
          inReplyToPostId={postId}
          onClose={() => setReplying(false)}
          onPublished={onClose}
        />
      )}
    </>
  );
}
