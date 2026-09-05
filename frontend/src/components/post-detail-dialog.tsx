import { ChevronDown, ShieldAlert } from "lucide-react";
import { type CSSProperties, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";
import {
  defaultDisplayPreferences,
  type DisplayPreferences,
  type ReplySort,
} from "../model/layout";
import { loadPostDetail, type PostDetail } from "../model/post-detail";
import { buildReplyThreadLayout, type ReplyThreadLayoutItem } from "../model/reply-thread-layout";
import type { TimelinePost } from "../model/timeline";
import { usePostDetailNavigation } from "../model/use-post-detail-navigation";
import { ComposerDialog } from "./composer-dialog";
import { Modal } from "./modal";
import { PostCard } from "./post-card";
import { ImageViewer } from "./image-viewer";
import { usePostTranslationSettings } from "./post-translation-context";

interface PostDetailDialogProps {
  postId: string;
  initialPost?: TimelinePost;
  accountId: string;
  translation: Translation;
  onClose: () => void;
  display?: DisplayPreferences;
  onOpenUser?: (userId: string) => void;
}

interface ImageSelection {
  src: string;
  sources: string[];
}

const REPLY_THREAD_STEP = 18;
const COMPACT_REPLY_THREAD_STEP = 14;

export function PostDetailDialog({
  postId: rootPostId,
  initialPost: rootInitialPost,
  accountId,
  translation,
  onClose,
  display = defaultDisplayPreferences,
  onOpenUser,
}: PostDetailDialogProps) {
  const { postId, initialPost, open, close } = usePostDetailNavigation(
    rootPostId,
    rootInitialPost,
    onClose,
  );
  const contentRef = useRef<HTMLDivElement>(null);
  const cachedDetails = useRef(
    new Map<
      string,
      { detail: PostDetail; scrollTop: number; showPossibleSpam: boolean; cursors: Set<string> }
    >(),
  );
  const pendingScroll = useRef<number | null>(null);
  const [detail, setDetail] = useState<PostDetail | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [replying, setReplying] = useState(false);
  const [imageSelection, setImageSelection] = useState<ImageSelection | null>(null);
  const [showPossibleSpam, setShowPossibleSpam] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState(false);
  const replyRequests = useRef({ cursors: new Set<string>(), loading: false, active: true });
  const { locale, replySort, setReplySort } = usePostTranslationSettings();
  const detailCacheKey = `${accountId}:${locale}:${replySort}:${postId}`;

  useEffect(() => {
    let active = true;
    const cached = cachedDetails.current.get(detailCacheKey);
    pendingScroll.current = cached?.scrollTop ?? 0;
    setDetail(
      cached?.detail ??
        (initialPost?.id === postId
          ? { post: initialPost, contextPosts: [], replies: [], nextCursor: null }
          : null),
    );
    setLoadingDetail(true);
    setError(null);
    setShowPossibleSpam(cached?.showPossibleSpam ?? false);
    setLoadingMore(false);
    setLoadMoreError(false);
    const requests = { cursors: new Set(cached?.cursors), loading: false, active: true };
    replyRequests.current = requests;
    if (cached !== undefined) {
      setLoadingDetail(false);
      return () => {
        requests.active = false;
      };
    }
    void loadPostDetail(accountId, postId, locale, replySort)
      .then((value) => {
        if (active) setDetail(value);
      })
      .catch(() => {
        if (active) setError(translation.timelineLoadError);
      })
      .finally(() => {
        if (active) setLoadingDetail(false);
      });
    return () => {
      active = false;
      requests.active = false;
    };
  }, [
    accountId,
    detailCacheKey,
    initialPost,
    locale,
    postId,
    replySort,
    translation.timelineLoadError,
  ]);

  useLayoutEffect(() => {
    if (detail?.post.id !== postId || pendingScroll.current === null) return;
    const panel = contentRef.current?.closest(".modal-panel");
    if (panel !== null && panel !== undefined) panel.scrollTop = pendingScroll.current;
    pendingScroll.current = null;
  }, [detail, postId]);

  const onOpenPost = (nextId: string) => {
    if (detail !== null && !loadingDetail && error === null)
      cachedDetails.current.set(detailCacheKey, {
        detail,
        scrollTop: contentRef.current?.closest(".modal-panel")?.scrollTop ?? 0,
        showPossibleSpam,
        cursors: new Set(
          [...replyRequests.current.cursors].filter(
            (cursor) => !replyRequests.current.loading || cursor !== detail.nextCursor?.trim(),
          ),
        ),
      });
    const post = [detail?.post, ...(detail?.contextPosts ?? []), ...(detail?.replies ?? [])].find(
      (item) => item?.id === nextId,
    );
    open(nextId, post);
  };

  const loadMoreReplies = async () => {
    const cursor = detail?.nextCursor?.trim();
    const requests = replyRequests.current;
    if (cursor === undefined || cursor.length === 0 || requests.loading || !requests.active) return;
    if (requests.cursors.has(cursor)) {
      setDetail((current) => (current === null ? current : { ...current, nextCursor: null }));
      return;
    }
    requests.cursors.add(cursor);
    requests.loading = true;
    setLoadingMore(true);
    setLoadMoreError(false);
    try {
      const next = await loadPostDetail(accountId, postId, locale, replySort, cursor);
      if (!requests.active) return;
      const nextCursor = next.nextCursor?.trim() || null;
      setDetail((current) => {
        if (current === null) return current;
        const replies = new Map(current.replies.map((reply) => [reply.id, reply]));
        for (const reply of next.replies) {
          replies.set(reply.id, reply);
        }
        return {
          ...current,
          replies: [...replies.values()],
          nextCursor: nextCursor !== null && requests.cursors.has(nextCursor) ? null : nextCursor,
        };
      });
    } catch {
      requests.cursors.delete(cursor);
      if (requests.active) setLoadMoreError(true);
    } finally {
      requests.loading = false;
      if (requests.active) setLoadingMore(false);
    }
  };

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
      <Modal
        title={translation.postDetail}
        closeLabel={translation.closeDetail}
        onClose={close}
        presentation="route"
      >
        <div className="post-detail-content" ref={contentRef} data-detail-post-id={postId}>
          {error !== null && detail === null ? (
            <p className="setup-error">{error}</p>
          ) : detail === null ? (
            <p>{translation.loading}</p>
          ) : (
            <>
              {detail.contextPosts?.map((contextPost) => (
                <div className="detail-context-post" key={contextPost.id}>
                  <PostCard
                    post={contextPost}
                    accountId={accountId}
                    translation={translation}
                    display={display}
                    onOpen={() => onOpenPost?.(contextPost.id)}
                    onOpenQuotedPost={onOpenPost}
                    onOpenUser={onOpenUser}
                    onOpenImage={openImage}
                  />
                </div>
              ))}
              <PostCard
                post={detail.post}
                accountId={accountId}
                translation={translation}
                display={display}
                onOpenQuotedPost={onOpenPost}
                onOpenUser={onOpenUser}
                onOpenImage={openImage}
              />
              {loadingDetail && <p className="detail-background-loading">{translation.loading}</p>}
              {error !== null && <p className="inline-error">{error}</p>}
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
              {!loadingDetail && detail.replies.length === 0 ? (
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
                      onOpenPost={onOpenPost}
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
                            onOpenPost={onOpenPost}
                            onOpenQuotedPost={onOpenPost}
                            onOpenUser={onOpenUser}
                            onOpenImage={openImage}
                          />
                        ))}
                    </section>
                  )}
                </>
              )}
              {detail.nextCursor !== null && (
                <div className="detail-load-more">
                  <button
                    className="secondary-button"
                    type="button"
                    disabled={loadingMore}
                    onClick={() => void loadMoreReplies()}
                  >
                    {loadingMore ? translation.loading : translation.loadMoreReplies}
                  </button>
                  {loadMoreError && (
                    <p className="inline-error">{translation.replyLoadMoreError}</p>
                  )}
                </div>
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
  onOpenPost: (postId: string) => void;
  onOpenQuotedPost?: (postId: string) => void;
  onOpenUser?: (userId: string) => void;
  onOpenImage: (media: TimelinePost["media"][number], siblings: TimelinePost["media"]) => void;
}

function ReplyThreadPost({
  thread,
  accountId,
  translation,
  display,
  onOpenPost,
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
        onOpen={() => onOpenPost(thread.reply.id)}
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
