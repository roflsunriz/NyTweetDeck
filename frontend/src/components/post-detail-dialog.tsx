import { ChevronDown, ShieldAlert } from "lucide-react";
import { type CSSProperties, useEffect, useMemo, useState } from "react";
import type { Translation } from "../i18n/translations";
import {
  defaultDisplayPreferences,
  type DisplayPreferences,
  type ReplySort,
} from "../model/layout";
import { loadPostDetail, type PostDetail } from "../model/post-detail";
import { buildReplyThreadLayout, type ReplyThreadLayoutItem } from "../model/reply-thread-layout";
import type { TimelinePost } from "../model/timeline";
import { useOverlayRoute } from "../model/use-overlay-route";
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

interface ImageSelection {
  src: string;
  sources: string[];
}

const REPLY_THREAD_STEP = 18;
const COMPACT_REPLY_THREAD_STEP = 14;

export function PostDetailDialog({
  postId,
  accountId,
  translation,
  onClose,
  display = defaultDisplayPreferences,
  onOpenPost,
  onOpenUser,
}: PostDetailDialogProps) {
  const close = useOverlayRoute(`post/${encodeURIComponent(postId)}`, onClose);
  const [detail, setDetail] = useState<PostDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [replying, setReplying] = useState(false);
  const [imageSelection, setImageSelection] = useState<ImageSelection | null>(null);
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

  const threadedReplies = useMemo(
    () => buildReplyThreadLayout(detail?.replies ?? [], postId),
    [detail?.replies, postId],
  );
  const regularReplies = threadedReplies.filter(({ reply }) => !isPossibleSpam(reply));
  const possibleSpamReplies = threadedReplies.filter(({ reply }) => isPossibleSpam(reply));
  const openImage = (
    media: PostDetail["post"]["media"][number],
    siblings: PostDetail["post"]["media"],
  ) => {
    setImageSelection({
      src: media.url,
      sources: siblings.filter((item) => item.type === "photo").map((item) => item.url),
    });
  };

  return (
    <>
      <Modal title={translation.postDetail} closeLabel={translation.closeDetail} onClose={close}>
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
                onOpenImage={openImage}
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
                  {regularReplies.map((thread) => (
                    <ReplyThreadPost
                      key={thread.reply.id}
                      thread={thread}
                      accountId={accountId}
                      translation={translation}
                      display={display}
                      onOpenQuotedPost={onOpenPost}
                      onOpenUser={onOpenUser}
                      onOpenImage={openImage}
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
                        possibleSpamReplies.map((thread) => (
                          <ReplyThreadPost
                            key={thread.reply.id}
                            thread={thread}
                            accountId={accountId}
                            translation={translation}
                            display={display}
                            onOpenQuotedPost={onOpenPost}
                            onOpenUser={onOpenUser}
                            onOpenImage={openImage}
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
          onPublished={close}
        />
      )}
      {imageSelection !== null && (
        <ImageViewer
          src={imageSelection.src}
          sources={imageSelection.sources}
          translation={translation}
          onClose={() => setImageSelection(null)}
        />
      )}
    </>
  );
}

function isPossibleSpam(reply: PostDetail["replies"][number]): boolean {
  return (
    reply.conversationSection === "LowQuality" || reply.conversationSection === "AbusiveQuality"
  );
}

interface ReplyThreadPostProps {
  thread: ReplyThreadLayoutItem<TimelinePost>;
  accountId: string;
  translation: Translation;
  display: DisplayPreferences;
  onOpenQuotedPost?: (postId: string) => void;
  onOpenUser?: (userId: string) => void;
  onOpenImage: (media: TimelinePost["media"][number], siblings: TimelinePost["media"]) => void;
}

function ReplyThreadPost({
  thread,
  accountId,
  translation,
  display,
  onOpenQuotedPost,
  onOpenUser,
  onOpenImage,
}: ReplyThreadPostProps) {
  return (
    <div
      className="reply-thread-item"
      data-reply-thread-id={thread.reply.id}
      data-thread-depth={thread.depth}
      data-thread-ancestors={thread.ancestorIds.join(",")}
      data-thread-last-sibling={thread.isLastSibling}
      data-thread-leaf={!thread.hasChildren}
      data-thread-depth-capped={thread.depthCapped}
      style={replyThreadItemStyle(thread.depth)}
    >
      <span className="reply-thread-connectors" aria-hidden="true">
        {thread.ancestorIds.map((ancestorId, level) => (
          <span
            className="reply-thread-guide"
            data-reply-thread-guide={level}
            data-thread-continues={thread.ancestorLines[level] === true}
            key={`${thread.reply.id}-guide-${ancestorId}`}
            style={replyThreadLevelStyle(level)}
          />
        ))}
        <span
          className="reply-thread-branch"
          data-reply-thread-branch="line"
          style={replyThreadLevelStyle(thread.depth)}
        />
        <span
          className="reply-thread-branch-arm"
          data-reply-thread-branch="arm"
          style={replyThreadLevelStyle(thread.depth)}
        />
      </span>
      <PostCard
        post={thread.reply}
        accountId={accountId}
        translation={translation}
        display={display}
        onOpenQuotedPost={onOpenQuotedPost}
        onOpenUser={onOpenUser}
        onOpenImage={onOpenImage}
      />
    </div>
  );
}

function replyThreadItemStyle(depth: number): CSSProperties {
  return {
    "--reply-thread-depth": depth,
    "--reply-thread-indent": `${(depth + 1) * REPLY_THREAD_STEP}px`,
    "--reply-thread-compact-indent": `${(depth + 1) * COMPACT_REPLY_THREAD_STEP}px`,
  } as CSSProperties;
}

function replyThreadLevelStyle(level: number): CSSProperties {
  return {
    "--reply-thread-level": level,
    "--reply-thread-level-offset": `${level * REPLY_THREAD_STEP + REPLY_THREAD_STEP / 2}px`,
    "--reply-thread-compact-level-offset": `${
      level * COMPACT_REPLY_THREAD_STEP + COMPACT_REPLY_THREAD_STEP / 2
    }px`,
  } as CSSProperties;
}
