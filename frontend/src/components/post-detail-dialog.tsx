import { useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import { defaultDisplayPreferences, type DisplayPreferences } from "../model/layout";
import { loadPostDetail, type PostDetail } from "../model/post-detail";
import { ComposerDialog } from "./composer-dialog";
import { Modal } from "./modal";
import { PostCard } from "./post-card";

interface PostDetailDialogProps {
  postId: string;
  accountId: string;
  translation: Translation;
  onClose: () => void;
  display?: DisplayPreferences;
  onOpenPost?: (postId: string) => void;
  onOpenUser?: (userId: string) => void;
}

export function PostDetailDialog({
  postId,
  accountId,
  translation,
  onClose,
  display = defaultDisplayPreferences,
  onOpenPost,
  onOpenUser,
}: PostDetailDialogProps) {
  const [detail, setDetail] = useState<PostDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [replying, setReplying] = useState(false);

  useEffect(() => {
    let active = true;
    setDetail(null);
    setError(null);
    void loadPostDetail(accountId, postId)
      .then((value) => {
        if (active) setDetail(value);
      })
      .catch(() => {
        if (active) setError(translation.timelineLoadError);
      });
    return () => {
      active = false;
    };
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
              <PostCard
                post={detail.post}
                accountId={accountId}
                translation={translation}
                display={display}
                onOpenQuotedPost={onOpenPost}
                onOpenUser={onOpenUser}
              />
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
                    display={display}
                    onOpenQuotedPost={onOpenPost}
                    onOpenUser={onOpenUser}
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
