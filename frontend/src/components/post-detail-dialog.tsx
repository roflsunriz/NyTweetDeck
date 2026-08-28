import { ChevronDown, ShieldAlert } from "lucide-react";
import { useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import {
  defaultDisplayPreferences,
  type DisplayPreferences,
  type ReplySort,
} from "../model/layout";
import { loadPostDetail, type PostDetail } from "../model/post-detail";
import { ComposerDialog } from "./composer-dialog";
import { Modal } from "./modal";
import { PostCard } from "./post-card";
import { ImageViewer } from "./image-viewer";
import { usePostTranslationSettings } from "./post-translation-context";

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
  const [imageUrl, setImageUrl] = useState<string | null>(null);
  const [showPossibleSpam, setShowPossibleSpam] = useState(false);
  const { locale, replySort, setReplySort } = usePostTranslationSettings();

  useEffect(() => {
    let active = true;
    setDetail(null);
    setError(null);
    setShowPossibleSpam(false);
    void loadPostDetail(accountId, postId, locale, replySort)
      .then((value) => {
        if (active) setDetail(value);
      })
      .catch(() => {
        if (active) setError(translation.timelineLoadError);
      });
    return () => {
      active = false;
    };
  }, [accountId, locale, postId, replySort, translation.timelineLoadError]);

  const regularReplies = detail?.replies.filter((reply) => !isPossibleSpam(reply)) ?? [];
  const possibleSpamReplies = detail?.replies.filter(isPossibleSpam) ?? [];

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
                onOpenImage={(media) => setImageUrl(media.url)}
              />
              <button
                className="primary-button detail-reply-button"
                type="button"
                onClick={() => setReplying(true)}
              >
                {translation.reply}
              </button>
              <div className="detail-replies-header">
                <h3>{translation.replies}</h3>
                <label className="reply-sort-control">
                  <span>{translation.replySort}</span>
                  <select
                    data-testid="reply-sort"
                    value={replySort}
                    onChange={(event) => setReplySort(event.target.value as ReplySort)}
                  >
                    <option value="relevance">{translation.replySortRelevance}</option>
                    <option value="recency">{translation.replySortRecency}</option>
                    <option value="likes">{translation.replySortLikes}</option>
                  </select>
                </label>
              </div>
              {detail.replies.length === 0 ? (
                <p>{translation.noPosts}</p>
              ) : (
                <>
                  {regularReplies.map((reply) => (
                    <PostCard
                      key={reply.id}
                      post={reply}
                      accountId={accountId}
                      translation={translation}
                      display={display}
                      onOpenQuotedPost={onOpenPost}
                      onOpenUser={onOpenUser}
                      onOpenImage={(media) => setImageUrl(media.url)}
                    />
                  ))}
                  {possibleSpamReplies.length > 0 && (
                    <section className="possible-spam-replies">
                      <button
                        className="possible-spam-toggle"
                        type="button"
                        aria-expanded={showPossibleSpam}
                        aria-label={translation.togglePossibleSpamReplies(
                          showPossibleSpam,
                          possibleSpamReplies.length,
                        )}
                        onClick={() => setShowPossibleSpam((current) => !current)}
                      >
                        <ShieldAlert aria-hidden="true" size={17} />
                        <span>{translation.possibleSpamReplies(possibleSpamReplies.length)}</span>
                        <ChevronDown
                          aria-hidden="true"
                          className={showPossibleSpam ? "expanded" : undefined}
                          size={17}
                        />
                      </button>
                      {showPossibleSpam &&
                        possibleSpamReplies.map((reply) => (
                          <PostCard
                            key={reply.id}
                            post={reply}
                            accountId={accountId}
                            translation={translation}
                            display={display}
                            onOpenQuotedPost={onOpenPost}
                            onOpenUser={onOpenUser}
                            onOpenImage={(media) => setImageUrl(media.url)}
                          />
                        ))}
                    </section>
                  )}
                </>
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
      {imageUrl !== null && (
        <ImageViewer src={imageUrl} translation={translation} onClose={() => setImageUrl(null)} />
      )}
    </>
  );
}

function isPossibleSpam(reply: PostDetail["replies"][number]): boolean {
  return (
    reply.conversationSection === "LowQuality" || reply.conversationSection === "AbusiveQuality"
  );
}
