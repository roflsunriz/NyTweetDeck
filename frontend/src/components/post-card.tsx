import {
  BarChart3,
  Bot,
  Bookmark,
  Download,
  Heart,
  MessageCircle,
  MoreHorizontal,
  Repeat2,
  Settings,
  Share2,
} from "lucide-react";
import { type KeyboardEvent, type MouseEvent, useEffect, useState } from "react";
import type { Translation } from "../i18n/translations";
import { defaultDisplayPreferences, type DisplayPreferences } from "../model/layout";
import {
  loadPostTranslation,
  shouldTranslatePost,
  translationTargetsLocale,
} from "../model/post-translation";
import { useRelativeTime } from "../model/relative-time";
import { ComposerDialog } from "./composer-dialog";
import { usePostTranslationSettings } from "./post-translation-context";

export interface TimelinePost {
  id: string;
  text: string;
  language: string | null;
  createdAt: string | null;
  author: {
    id: string;
    username: string;
    displayName: string;
    avatarUrl: string | null;
    verified: boolean;
  };
  repostedBy: TimelinePost["author"] | null;
  replyCount: number;
  repostCount: number;
  quoteCount: number;
  likeCount: number;
  bookmarkCount: number;
  viewCount: number;
  liked: boolean;
  reposted: boolean;
  bookmarked: boolean;
  replyToPostId: string | null;
  replyToUsername: string | null;
  quotedPost: EmbeddedPost | null;
  preTranslated?: PreTranslatedPost | null;
  media: Array<{ id: string; type: string; url: string; previewUrl: string }>;
}

interface PreTranslatedPost {
  text: string;
  sourceLanguage: string | null;
  targetLanguage: string;
  provider: "Grok";
}

interface EmbeddedPost {
  id: string;
  text: string;
  language: string | null;
  createdAt: string | null;
  author: TimelinePost["author"];
  media: TimelinePost["media"];
}

interface PostCardProps {
  post: TimelinePost;
  accountId: string;
  translation: Translation;
  onOpen?: () => void;
  onOpenUser?: (userId: string) => void;
  onOpenQuotedPost?: (postId: string) => void;
  display?: DisplayPreferences;
}

export function PostCard({
  post,
  accountId,
  translation,
  onOpen,
  onOpenUser,
  onOpenQuotedPost,
  display = defaultDisplayPreferences,
}: PostCardProps) {
  const [liked, setLiked] = useState(post.liked);
  const [reposted, setReposted] = useState(post.reposted);
  const [bookmarked, setBookmarked] = useState(post.bookmarked);
  const [likeCount, setLikeCount] = useState(post.likeCount);
  const [repostCount, setRepostCount] = useState(post.repostCount + post.quoteCount);
  const [bookmarkCount, setBookmarkCount] = useState(post.bookmarkCount);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [replying, setReplying] = useState(false);
  const [quoting, setQuoting] = useState(false);
  const [hidden, setHidden] = useState(false);
  const [fetchedTranslatedText, setFetchedTranslatedText] = useState<string | null>(null);
  const [fetchedTranslationProvider, setFetchedTranslationProvider] = useState<string | null>(null);
  const [translationLoading, setTranslationLoading] = useState(false);
  const [translationError, setTranslationError] = useState(false);
  const [showOriginal, setShowOriginal] = useState(false);
  const [translationAttempt, setTranslationAttempt] = useState(0);
  const { locale, autoTranslatePosts, setAutoTranslatePosts } = usePostTranslationSettings();
  const time = useRelativeTime(post.createdAt, document.documentElement.lang || "en");
  const postUrl = `https://x.com/${post.author.username}/status/${post.id}`;
  const preTranslated =
    post.preTranslated !== undefined &&
    post.preTranslated !== null &&
    translationTargetsLocale(post.preTranslated.targetLanguage, locale)
      ? post.preTranslated
      : null;
  const translationNeeded =
    post.text.trim().length > 0 &&
    (preTranslated !== null || shouldTranslatePost(post.language, locale));
  const translatedText = preTranslated?.text ?? fetchedTranslatedText;
  const translationProvider = preTranslated?.provider ?? fetchedTranslationProvider;
  const visibleText =
    autoTranslatePosts && translatedText !== null && !showOriginal ? translatedText : post.text;

  useEffect(() => {
    if (!autoTranslatePosts || !translationNeeded) {
      setTranslationLoading(false);
      setTranslationError(false);
      setShowOriginal(false);
      return;
    }
    if (preTranslated !== null) {
      setFetchedTranslatedText(null);
      setFetchedTranslationProvider(null);
      setTranslationLoading(false);
      setTranslationError(false);
      setShowOriginal(false);
      return;
    }
    if (post.language === null) {
      setTranslationLoading(false);
      setTranslationError(false);
      setShowOriginal(false);
      return;
    }
    let active = true;
    setFetchedTranslatedText(null);
    setFetchedTranslationProvider(null);
    setTranslationLoading(true);
    setTranslationError(false);
    setShowOriginal(false);
    void loadPostTranslation({
      accountId,
      postId: post.id,
      sourceLanguage: post.language,
      targetLanguage: locale,
      force: translationAttempt > 0,
    })
      .then((result) => {
        if (!active) return;
        setFetchedTranslatedText(result.text);
        setFetchedTranslationProvider(result.provider);
      })
      .catch(() => {
        if (active) setTranslationError(true);
      })
      .finally(() => {
        if (active) setTranslationLoading(false);
      });
    return () => {
      active = false;
    };
  }, [
    accountId,
    autoTranslatePosts,
    locale,
    post.id,
    post.language,
    preTranslated,
    translationAttempt,
    translationNeeded,
  ]);

  const mutate = async (action: string, onSuccess: () => void) => {
    setBusyAction(action);
    setActionError(null);
    try {
      const response = await fetch(
        `/api/v1/posts/${post.id}/actions/${action}?accountId=${encodeURIComponent(accountId)}`,
        { method: "POST" },
      );
      if (!response.ok) {
        let detail: string | null = null;
        try {
          const problem = (await response.json()) as { detail?: unknown };
          if (typeof problem.detail === "string" && problem.detail.length > 0) {
            detail = problem.detail;
          }
        } catch {
          // Use the localized fallback when the response has no problem body.
        }
        throw new Error(detail ?? `HTTP ${response.status}`);
      }
      onSuccess();
    } catch (error) {
      const detail = error instanceof Error ? error.message : "";
      setActionError(
        detail.length > 0 && !detail.startsWith("HTTP ")
          ? `${translation.postActionFailed} ${detail}`
          : translation.postActionFailed,
      );
    } finally {
      setBusyAction(null);
    }
  };
  const share = async () => {
    if (navigator.share !== undefined) {
      await navigator.share({ title: post.author.displayName, text: post.text, url: postUrl });
    } else {
      await navigator.clipboard.writeText(postUrl);
    }
  };
  const openFromCard = (event: MouseEvent<HTMLElement>) => {
    if (onOpen === undefined) {
      return;
    }
    const target = event.target;
    if (
      target instanceof Element &&
      target.closest("button, a, summary, input, select, textarea")
    ) {
      return;
    }
    onOpen();
  };
  const openFromKeyboard = (event: KeyboardEvent<HTMLElement>) => {
    if (onOpen !== undefined && event.target === event.currentTarget && event.key === "Enter") {
      onOpen();
    }
  };

  if (hidden) {
    return null;
  }

  return (
    <>
      <article
        className="post-card"
        data-post-id={post.id}
        aria-label={onOpen === undefined ? undefined : translation.postDetail}
        tabIndex={onOpen === undefined ? undefined : 0}
        onClick={openFromCard}
        onKeyDown={openFromKeyboard}
      >
        {post.repostedBy != null && (
          <button
            className="repost-context"
            type="button"
            disabled={onOpenUser === undefined || post.repostedBy.id.length === 0}
            onClick={(event) => {
              event.stopPropagation();
              onOpenUser?.(post.repostedBy?.id ?? "");
            }}
          >
            <Repeat2 aria-hidden="true" size={13} />
            <span>{translation.repostedBy(post.repostedBy.displayName)}</span>
          </button>
        )}
        <header>
          <button
            className="post-author-button"
            type="button"
            disabled={onOpenUser === undefined || post.author.id.length === 0}
            onClick={(event) => {
              event.stopPropagation();
              onOpenUser?.(post.author.id);
            }}
          >
            {post.author.avatarUrl !== null ? (
              <img src={post.author.avatarUrl} alt="" loading="lazy" />
            ) : (
              <span className="avatar-placeholder" aria-hidden="true" />
            )}
            <span className="post-author-text">
              <strong>{post.author.displayName}</strong>
              <span>@{post.author.username}</span>
            </span>
          </button>
          {time !== null && <time dateTime={post.createdAt ?? undefined}>{time}</time>}
          <button
            className={`post-header-action post-translation-toggle${autoTranslatePosts ? " active" : ""}`}
            type="button"
            aria-label={
              autoTranslatePosts
                ? translation.disableAutoTranslation
                : translation.enableAutoTranslation
            }
            aria-pressed={autoTranslatePosts}
            title={
              autoTranslatePosts
                ? translation.disableAutoTranslation
                : translation.enableAutoTranslation
            }
            onClick={() => setAutoTranslatePosts(!autoTranslatePosts)}
          >
            <Settings aria-hidden="true" size={16} />
          </button>
          <a
            className="post-header-action"
            aria-label={translation.askGrok}
            href={`https://x.com/i/grok?text=${encodeURIComponent(`Analyze this post: ${postUrl}`)}`}
            target="_blank"
            rel="noreferrer"
          >
            <Bot aria-hidden="true" size={16} />
          </a>
          <PostMenu
            accountId={accountId}
            userId={post.author.id}
            postUrl={postUrl}
            translation={translation}
            onHide={() => setHidden(true)}
          />
        </header>
        {post.replyToPostId !== null && (
          <button
            className="reply-context"
            type="button"
            disabled={onOpenQuotedPost === undefined}
            onClick={(event) => {
              event.stopPropagation();
              onOpenQuotedPost?.(post.replyToPostId ?? "");
            }}
          >
            <MessageCircle aria-hidden="true" size={13} />
            <span>
              {post.replyToUsername === null
                ? translation.replyingToPost
                : translation.replyingTo(post.replyToUsername)}
            </span>
          </button>
        )}
        {onOpen === undefined ? (
          <p className="post-text">{renderPostText(visibleText)}</p>
        ) : (
          <button className="post-open-button post-text" type="button" onClick={onOpen}>
            {renderPostText(visibleText)}
          </button>
        )}
        {autoTranslatePosts && translationNeeded && (
          <div className="post-translation-status" aria-live="polite">
            {translationLoading && <span>{translation.translationLoading}</span>}
            {translationError && (
              <>
                <span className="post-translation-error">{translation.translationFailed}</span>
                <button type="button" onClick={() => setTranslationAttempt((value) => value + 1)}>
                  {translation.retry}
                </button>
              </>
            )}
            {translatedText !== null && translationProvider !== null && (
              <>
                <span>{translation.translatedBy(translationProvider)}</span>
                <button type="button" onClick={() => setShowOriginal((value) => !value)}>
                  {showOriginal ? translation.showTranslation : translation.showOriginal}
                </button>
              </>
            )}
          </div>
        )}
        {post.media.length > 0 && display.mediaPreview && (
          <div className="post-media">
            {post.media.map((media) =>
              media.type === "video" || media.type === "animated_gif" ? (
                <video
                  key={media.id}
                  controls
                  muted
                  autoPlay={display.videoAutoplay}
                  preload="metadata"
                  poster={media.previewUrl}
                  src={media.url}
                />
              ) : (
                <img key={media.id} loading="lazy" src={media.url} alt="" />
              ),
            )}
          </div>
        )}
        {post.media.length > 0 && !display.mediaPreview && (
          <div className="post-media-links">
            {post.media.map((media) => (
              <a key={media.id} href={media.url} target="_blank" rel="noreferrer">
                {translation.viewMedia}
              </a>
            ))}
          </div>
        )}
        {post.quotedPost != null && (
          <QuotedPostCard
            post={post.quotedPost}
            translation={translation}
            display={display}
            onOpen={onOpenQuotedPost}
          />
        )}
        <footer className="post-actions">
          <Action
            actionId="reply"
            icon={MessageCircle}
            label={translation.reply}
            count={post.replyCount}
            onClick={() => setReplying(true)}
          />
          <RepostMenu
            label={translation.repost}
            quoteLabel={translation.quote}
            count={repostCount}
            active={reposted}
            disabled={busyAction !== null}
            onRepost={() =>
              mutate(reposted ? "undoRepost" : "repost", () => {
                setReposted((current) => !current);
                setRepostCount((current) => Math.max(0, current + (reposted ? -1 : 1)));
              })
            }
            onQuote={() => setQuoting(true)}
          />
          <Action
            actionId="like"
            icon={Heart}
            label={translation.like}
            count={likeCount}
            active={liked}
            disabled={busyAction !== null}
            onClick={() =>
              mutate(liked ? "unlike" : "like", () => {
                setLiked((current) => !current);
                setLikeCount((current) => Math.max(0, current + (liked ? -1 : 1)));
              })
            }
          />
          <Action
            actionId="views"
            disabled
            icon={BarChart3}
            label={translation.views}
            count={post.viewCount}
          />
          <Action
            actionId="bookmark"
            icon={Bookmark}
            label={translation.bookmark}
            count={bookmarkCount}
            active={bookmarked}
            disabled={busyAction !== null}
            onClick={() =>
              mutate(bookmarked ? "removeBookmark" : "bookmark", () => {
                setBookmarked((current) => !current);
                setBookmarkCount((current) => Math.max(0, current + (bookmarked ? -1 : 1)));
              })
            }
          />
          <button
            type="button"
            className="post-action"
            aria-label={translation.share}
            onClick={share}
          >
            <Share2 aria-hidden="true" size={16} />
          </button>
          {post.media[0] !== undefined && (
            <a
              className="post-action"
              aria-label={translation.downloadMedia}
              href={post.media[0].url}
              download
            >
              <Download aria-hidden="true" size={16} />
            </a>
          )}
        </footer>
        {actionError !== null && <p className="post-action-error">{actionError}</p>}
      </article>
      {replying && (
        <ComposerDialog
          translation={translation}
          accountId={accountId}
          inReplyToPostId={post.id}
          onClose={() => setReplying(false)}
        />
      )}
      {quoting && (
        <ComposerDialog
          translation={translation}
          accountId={accountId}
          quotePostId={post.id}
          quotePostUrl={postUrl}
          onClose={() => setQuoting(false)}
        />
      )}
    </>
  );
}

function QuotedPostCard({
  post,
  translation,
  display,
  onOpen,
}: {
  post: EmbeddedPost;
  translation: Translation;
  display: DisplayPreferences;
  onOpen?: (postId: string) => void;
}) {
  const time = useRelativeTime(post.createdAt, document.documentElement.lang || "en");
  return (
    <button
      className="quoted-post-card"
      type="button"
      disabled={onOpen === undefined}
      aria-label={`${translation.postDetail}: ${post.author.displayName}, ${post.text}`}
      onClick={() => onOpen?.(post.id)}
    >
      <span className="quoted-post-header">
        {post.author.avatarUrl !== null ? (
          <img src={post.author.avatarUrl} alt="" loading="lazy" />
        ) : (
          <span className="quoted-avatar-placeholder" aria-hidden="true" />
        )}
        <span className="quoted-post-author">
          <strong>{post.author.displayName}</strong>
          <span>@{post.author.username}</span>
        </span>
        {time !== null && (
          <time className="quoted-post-time" dateTime={post.createdAt ?? undefined}>
            {time}
          </time>
        )}
      </span>
      <span className="quoted-post-text">{renderPostText(post.text)}</span>
      {display.mediaPreview && post.media.length > 0 && (
        <span className="quoted-post-media">
          {post.media.map((media) => (
            <img key={media.id} src={media.previewUrl || media.url} alt="" loading="lazy" />
          ))}
        </span>
      )}
    </button>
  );
}

function RepostMenu({
  label,
  quoteLabel,
  count,
  active,
  disabled,
  onRepost,
  onQuote,
}: {
  label: string;
  quoteLabel: string;
  count: number;
  active: boolean;
  disabled: boolean;
  onRepost: () => void;
  onQuote: () => void;
}) {
  const closeAndRun = (event: MouseEvent<HTMLButtonElement>, action: () => void) => {
    event.currentTarget.closest("details")?.removeAttribute("open");
    action();
  };
  return (
    <details className="repost-menu">
      <summary
        className={`post-action${active ? " active" : ""}${disabled ? " disabled" : ""}`}
        data-post-action="repost"
        aria-label={label}
        aria-disabled={disabled}
      >
        <Repeat2 aria-hidden="true" size={16} />
        <span>{compactNumber(count)}</span>
      </summary>
      <div>
        <button
          type="button"
          data-post-action="repost-confirm"
          disabled={disabled}
          onClick={(event) => closeAndRun(event, onRepost)}
        >
          <Repeat2 aria-hidden="true" size={16} />
          {label}
        </button>
        <button type="button" disabled={disabled} onClick={(event) => closeAndRun(event, onQuote)}>
          <MessageCircle aria-hidden="true" size={16} />
          {quoteLabel}
        </button>
      </div>
    </details>
  );
}

function renderPostText(text: string) {
  let offset = 0;
  return text.split(/(#[\p{L}\p{N}_]+)/gu).map((segment) => {
    const start = offset;
    offset += segment.length;
    return segment.startsWith("#") ? (
      <span className="hashtag" key={`${start}-${segment}`}>
        {segment}
      </span>
    ) : (
      segment
    );
  });
}

function PostMenu({
  accountId,
  userId,
  postUrl,
  translation,
  onHide,
}: {
  accountId: string;
  userId: string;
  postUrl: string;
  translation: Translation;
  onHide: () => void;
}) {
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [completedAction, setCompletedAction] = useState<string | null>(null);
  const [error, setError] = useState(false);
  const [listEditor, setListEditor] = useState(false);
  const [listId, setListId] = useState("");
  const links = [
    [translation.postActivity, `${postUrl}/analytics`],
    [translation.embedPost, `https://publish.twitter.com/#query=${encodeURIComponent(postUrl)}`],
    [
      translation.reportPost,
      `https://x.com/i/safety/report_story?tweet_id=${postUrl.split("/").at(-1)}`,
    ],
    [translation.requestCommunityNote, "https://x.com/i/communitynotes"],
  ] as const;
  const userAction = async (action: "follow" | "mute" | "block") => {
    if (action === "block" && !window.confirm(translation.confirmBlock)) {
      return;
    }
    setBusyAction(action);
    setCompletedAction(null);
    setError(false);
    try {
      const response = await fetch(
        `/api/v1/users/${encodeURIComponent(userId)}/actions/${action}?accountId=${encodeURIComponent(accountId)}`,
        { method: "POST" },
      );
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      setCompletedAction(action);
    } catch {
      setError(true);
    } finally {
      setBusyAction(null);
    }
  };
  const listAction = async (action: "add" | "remove") => {
    if (!/^\d{1,30}$/.test(listId)) {
      setError(true);
      return;
    }
    setBusyAction(`list-${action}`);
    setCompletedAction(null);
    setError(false);
    try {
      const response = await fetch(
        `/api/v1/users/${encodeURIComponent(userId)}/lists/${encodeURIComponent(listId)}/${action}?accountId=${encodeURIComponent(accountId)}`,
        { method: "POST" },
      );
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      setCompletedAction(`list-${action}`);
    } catch {
      setError(true);
    } finally {
      setBusyAction(null);
    }
  };
  return (
    <details className="post-overflow">
      <summary aria-label={translation.postMenu}>
        <MoreHorizontal aria-hidden="true" size={17} />
      </summary>
      <div>
        <button type="button" onClick={onHide}>
          {translation.notInterested}
        </button>
        {(
          [
            ["follow", translation.followUser],
            ["mute", translation.muteUser],
            ["block", translation.blockUser],
          ] as const
        ).map(([action, label]) => (
          <button
            key={action}
            type="button"
            disabled={busyAction !== null}
            onClick={() => userAction(action)}
          >
            {label}
            {completedAction === action ? ` · ${translation.userActionCompleted}` : ""}
          </button>
        ))}
        <button type="button" onClick={() => setListEditor((current) => !current)}>
          {translation.manageLists}
        </button>
        {listEditor && (
          <div className="list-membership-editor">
            <input
              aria-label={translation.listId}
              inputMode="numeric"
              pattern="[0-9]+"
              maxLength={30}
              value={listId}
              onChange={(event) => setListId(event.target.value)}
            />
            <button type="button" disabled={busyAction !== null} onClick={() => listAction("add")}>
              {translation.addToList}
              {completedAction === "list-add" ? ` · ${translation.userActionCompleted}` : ""}
            </button>
            <button
              type="button"
              disabled={busyAction !== null}
              onClick={() => listAction("remove")}
            >
              {translation.removeFromList}
              {completedAction === "list-remove" ? ` · ${translation.userActionCompleted}` : ""}
            </button>
          </div>
        )}
        {links.map(([label, href]) => (
          <a key={label} href={href} target="_blank" rel="noreferrer">
            {label}
          </a>
        ))}
        {error && <p className="post-menu-error">{translation.userActionFailed}</p>}
      </div>
    </details>
  );
}

interface ActionProps {
  actionId: string;
  icon: typeof MessageCircle;
  label: string;
  count: number;
  active?: boolean;
  disabled?: boolean;
  onClick?: () => void;
}

function Action({
  actionId,
  icon: Icon,
  label,
  count,
  active = false,
  disabled = false,
  onClick,
}: ActionProps) {
  return (
    <button
      type="button"
      className={`post-action${active ? " active" : ""}`}
      data-post-action={actionId}
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
    >
      <Icon aria-hidden="true" size={16} />
      <span>{compactNumber(count)}</span>
    </button>
  );
}

function compactNumber(value: number): string {
  return new Intl.NumberFormat(undefined, { notation: "compact", maximumFractionDigits: 1 }).format(
    value,
  );
}
