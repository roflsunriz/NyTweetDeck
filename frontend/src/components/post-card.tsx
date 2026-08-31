import {
  BarChart3,
  Bot,
  Bookmark,
  Download,
  Heart,
  MessageCircle,
  Repeat2,
  Settings,
  Share2,
} from "lucide-react";
import { type KeyboardEvent, type MouseEvent, useEffect, useId, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";
import { defaultDisplayPreferences, type DisplayPreferences } from "../model/layout";
import { useRelativeTime } from "../model/relative-time";
import type { CommunityNote, EmbeddedPost, TimelinePost } from "../model/timeline";
import { postTextSegments } from "../model/post-text-segments";
import { ComposerDialog } from "./composer-dialog";
import { ArticleCard } from "./article-card";
import { usePostTranslationSettings } from "./post-translation-context";
import { PostMenu } from "./post-menu";
import { useOptimisticToggle } from "./use-optimistic-toggle";
import { type PostTranslationView, usePostTranslation } from "./use-post-translation";

export type { TimelineArticle, TimelinePost } from "../model/timeline";

interface PostCardProps {
  post: TimelinePost;
  accountId: string;
  translation: Translation;
  onOpen?: () => void;
  onOpenUser?: (userId: string) => void;
  onOpenQuotedPost?: (postId: string) => void;
  onOpenImage?: (media: TimelinePost["media"][number], siblings: TimelinePost["media"]) => void;
  display?: DisplayPreferences;
}

export function PostCard({
  post,
  accountId,
  translation,
  onOpen,
  onOpenUser,
  onOpenQuotedPost,
  onOpenImage,
  display = defaultDisplayPreferences,
}: PostCardProps) {
  const [liked, setLiked] = useState(post.liked);
  const [reposted, setReposted] = useState(post.reposted);
  const [bookmarked, setBookmarked] = useState(post.bookmarked);
  const [likeCount, setLikeCount] = useState(post.likeCount);
  const [repostCount, setRepostCount] = useState(post.repostCount + post.quoteCount);
  const [bookmarkCount, setBookmarkCount] = useState(post.bookmarkCount);
  const [actionError, setActionError] = useState<string | null>(null);
  const [replying, setReplying] = useState(false);
  const [quoting, setQuoting] = useState(false);
  const [hidden, setHidden] = useState(false);
  const [translationInViewport, setTranslationInViewport] = useState(
    () => typeof globalThis.IntersectionObserver === "undefined",
  );
  const cardRef = useRef<HTMLElement | null>(null);
  const { autoTranslatePosts, setAutoTranslatePosts } = usePostTranslationSettings();
  const time = useRelativeTime(post.createdAt, document.documentElement.lang || "en");
  const postUrl = `https://x.com/${post.author.username}/status/${post.id}`;
  const postTranslation = usePostTranslation({
    accountId,
    postId: post.id,
    text: post.text,
    language: post.language,
    preTranslated: post.preTranslated,
    active: translationInViewport,
  });
  const visibleText = omitTrailingRedirectLink(
    postTranslation.visibleText,
    post.media.length > 0 || post.article != null,
  );
  const { pendingActions, reconcile, toggle } = useOptimisticToggle(
    async (action) => {
      setActionError(null);
      const response = await fetch(
        `/api/v1/posts/${post.id}/actions/${action}?accountId=${encodeURIComponent(accountId)}`,
        { method: "POST" },
      );
      if (response.ok) return;
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
    },
    (error) => {
      const detail = error instanceof Error ? error.message : "";
      setActionError(
        detail.length > 0 && !detail.startsWith("HTTP ")
          ? `${translation.postActionFailed} ${detail}`
          : translation.postActionFailed,
      );
    },
  );

  useEffect(() => {
    if (!reconcile("like", post.liked, post.likeCount)) {
      setLiked(post.liked);
      setLikeCount(post.likeCount);
    }
  }, [post.likeCount, post.liked, reconcile]);
  useEffect(() => {
    const count = post.repostCount + post.quoteCount;
    if (!reconcile("repost", post.reposted, count)) {
      setReposted(post.reposted);
      setRepostCount(count);
    }
  }, [post.quoteCount, post.repostCount, post.reposted, reconcile]);
  useEffect(() => {
    if (!reconcile("bookmark", post.bookmarked, post.bookmarkCount)) {
      setBookmarked(post.bookmarked);
      setBookmarkCount(post.bookmarkCount);
    }
  }, [post.bookmarkCount, post.bookmarked, reconcile]);

  useEffect(() => {
    const target = cardRef.current;
    if (
      target === null ||
      target.dataset.postId !== post.id ||
      typeof globalThis.IntersectionObserver === "undefined"
    ) {
      setTranslationInViewport(true);
      return;
    }
    setTranslationInViewport(false);
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setTranslationInViewport(true);
          observer.disconnect();
        }
      },
      { rootMargin: "600px 0px" },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [post.id]);

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
        ref={cardRef}
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
        <p className="post-text">{renderPostText(visibleText)}</p>
        <PostTranslationStatus state={postTranslation} translation={translation} />
        {post.communityNote != null && (
          <aside className="community-note-card" data-testid="community-note-card">
            <strong>{post.communityNote.title || translation.communityNote}</strong>
            {post.communityNote.text !== null && (
              <p>{renderCommunityNoteText(post.communityNote)}</p>
            )}
            {post.communityNote.footer !== null && <small>{post.communityNote.footer}</small>}
          </aside>
        )}
        {post.media.length > 0 && display.mediaPreview && (
          <div className="post-media">
            {post.media.map((media) =>
              media.type === "video" || media.type === "animated_gif" ? (
                <ConfiguredVideo
                  key={media.id}
                  mediaId={media.id}
                  autoPlay={display.videoAutoplay}
                  loop={display.videoLoop}
                  volume={display.videoVolume}
                  poster={media.previewUrl}
                  src={media.url}
                />
              ) : onOpenImage === undefined ? (
                <img key={media.id} loading="lazy" src={media.url} alt="" />
              ) : (
                <button
                  className="post-image-open"
                  key={media.id}
                  type="button"
                  aria-label={translation.fullSizeImage}
                  onClick={() => onOpenImage(media, post.media)}
                >
                  <img loading="lazy" src={media.url} alt="" />
                </button>
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
        {post.article != null && (
          <ArticleCard
            article={post.article}
            postId={post.id}
            accountId={accountId}
            translation={translation}
          />
        )}
        {post.quotedPost != null && (
          <QuotedPostCard
            post={post.quotedPost}
            accountId={accountId}
            translation={translation}
            display={display}
            translationActive={translationInViewport}
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
            pending={pendingActions.has("repost")}
            onRepost={() =>
              toggle({
                actionKey: "repost",
                active: reposted,
                count: repostCount,
                setActive: setReposted,
                setCount: setRepostCount,
                enableAction: "repost",
                disableAction: "undoRepost",
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
            pending={pendingActions.has("like")}
            onClick={() =>
              toggle({
                actionKey: "like",
                active: liked,
                count: likeCount,
                setActive: setLiked,
                setCount: setLikeCount,
                enableAction: "like",
                disableAction: "unlike",
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
            pending={pendingActions.has("bookmark")}
            onClick={() =>
              toggle({
                actionKey: "bookmark",
                active: bookmarked,
                count: bookmarkCount,
                setActive: setBookmarked,
                setCount: setBookmarkCount,
                enableAction: "bookmark",
                disableAction: "removeBookmark",
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

function ConfiguredVideo({
  mediaId,
  autoPlay,
  loop,
  volume,
  poster,
  src,
}: {
  mediaId: string;
  autoPlay: boolean;
  loop: boolean;
  volume: number;
  poster: string;
  src: string;
}) {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const [activeSource, setActiveSource] = useState<string | null>(null);
  const [inPlaybackZone, setInPlaybackZone] = useState(false);

  useEffect(() => {
    const video = videoRef.current;
    if (video === null) return;
    setInPlaybackZone(false);
    setActiveSource(null);
    if (typeof globalThis.IntersectionObserver === "undefined") {
      setActiveSource(src);
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        const active = entries.some((entry) => entry.target === video && entry.isIntersecting);
        if (!active) {
          video.pause();
          video.removeAttribute("src");
          video.load();
        }
        setInPlaybackZone(active);
        setActiveSource(active ? src : null);
      },
      {
        root: null,
        rootMargin: "0px 0px -50% 0px",
        threshold: 0,
      },
    );
    observer.observe(video);
    return () => observer.disconnect();
  }, [src]);

  useEffect(() => {
    const video = videoRef.current;
    if (video === null) return;
    const normalizedVolume = Math.min(100, Math.max(0, volume)) / 100;
    video.volume = normalizedVolume;
  }, [volume]);

  const sourceActive = activeSource === src;
  useEffect(() => {
    const video = videoRef.current;
    if (video === null) return;
    if (!autoPlay || !inPlaybackZone || !sourceActive) {
      video.pause();
      return;
    }
    void video.play().catch((error: unknown) => {
      if (
        !(error instanceof DOMException) ||
        (error.name !== "AbortError" && error.name !== "NotAllowedError")
      ) {
        video.pause();
      }
    });
    return () => video.pause();
  }, [autoPlay, inPlaybackZone, sourceActive]);

  return (
    <video
      ref={videoRef}
      data-media-id={mediaId}
      data-viewport-active={inPlaybackZone}
      controls
      muted
      autoPlay={autoPlay && inPlaybackZone && sourceActive}
      loop={loop}
      preload={inPlaybackZone ? "metadata" : "none"}
      poster={poster}
      src={sourceActive ? src : undefined}
    />
  );
}

function PostTranslationStatus({
  state,
  translation,
  compact = false,
}: {
  state: PostTranslationView;
  translation: Translation;
  compact?: boolean;
}) {
  if (!state.autoTranslatePosts || !state.needed) return null;
  return (
    <div
      className={`post-translation-status${compact ? " quoted-post-translation-status" : ""}`}
      aria-live="polite"
    >
      {state.loading && (
        <span>
          {state.retrySeconds > 0
            ? translation.translationRetryScheduled(state.retrySeconds)
            : translation.translationLoading}
        </span>
      )}
      {state.error && (
        <>
          <span className="post-translation-error">{translation.translationFailed}</span>
          <button type="button" onClick={state.retry}>
            {translation.retry}
          </button>
        </>
      )}
      {state.translatedText !== null && state.provider !== null && (
        <>
          <span>{translation.translatedBy(state.provider)}</span>
          <button type="button" onClick={state.toggleOriginal}>
            {state.showOriginal ? translation.showTranslation : translation.showOriginal}
          </button>
        </>
      )}
    </div>
  );
}

function QuotedPostCard({
  post,
  accountId,
  translation,
  display,
  translationActive,
  onOpen,
}: {
  post: EmbeddedPost;
  accountId: string;
  translation: Translation;
  display: DisplayPreferences;
  translationActive: boolean;
  onOpen?: (postId: string) => void;
}) {
  const time = useRelativeTime(post.createdAt, document.documentElement.lang || "en");
  const postTranslation = usePostTranslation({
    accountId,
    postId: post.id,
    text: post.text,
    language: post.language,
    preTranslated: post.preTranslated,
    active: translationActive,
  });
  const visibleText = omitTrailingRedirectLink(
    postTranslation.visibleText,
    post.media.length > 0 || post.article != null,
  );
  return (
    <article className="quoted-post-card">
      <button
        className="quoted-post-open"
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
        {visibleText.length > 0 && (
          <span className="quoted-post-text">{renderPostText(visibleText)}</span>
        )}
        {display.mediaPreview && post.media.length > 0 && (
          <span className="quoted-post-media">
            {post.media.map((media) => (
              <img key={media.id} src={media.previewUrl || media.url} alt="" loading="lazy" />
            ))}
          </span>
        )}
      </button>
      <PostTranslationStatus state={postTranslation} translation={translation} compact />
      {post.article != null && (
        <ArticleCard
          article={post.article}
          postId={post.id}
          accountId={accountId}
          translation={translation}
        />
      )}
    </article>
  );
}

function RepostMenu({
  label,
  quoteLabel,
  count,
  active,
  pending,
  onRepost,
  onQuote,
}: {
  label: string;
  quoteLabel: string;
  count: number;
  active: boolean;
  pending: boolean;
  onRepost: () => void;
  onQuote: () => void;
}) {
  const menuId = useId();
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!open) return;
    const closeOnEscape = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [open]);
  const closeAndRun = (action: () => void) => {
    setOpen(false);
    action();
  };
  return (
    <div className="repost-menu">
      <button
        type="button"
        className={`post-action${active ? " active repost-active" : ""}${pending ? " pending" : ""}`}
        data-post-action="repost"
        aria-controls={menuId}
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label={label}
        aria-busy={pending}
        onClick={() => setOpen((current) => !current)}
      >
        <Repeat2 aria-hidden="true" size={16} />
        <span>{compactNumber(count)}</span>
      </button>
      {open && (
        <div id={menuId} role="menu">
          <button
            type="button"
            role="menuitem"
            data-post-action="repost-confirm"
            onClick={() => closeAndRun(onRepost)}
          >
            <Repeat2 aria-hidden="true" size={16} />
            {label}
          </button>
          <button type="button" role="menuitem" onClick={() => closeAndRun(onQuote)}>
            <MessageCircle aria-hidden="true" size={16} />
            {quoteLabel}
          </button>
        </div>
      )}
    </div>
  );
}

function renderPostText(text: string) {
  let offset = 0;
  return postTextSegments(text).map((segment) => {
    const start = offset;
    offset += segment.text.length;
    if (segment.kind === "hashtag") {
      return (
        <span className="hashtag" key={`${start}-${segment.text}`}>
          {segment.text}
        </span>
      );
    }
    if (segment.kind === "url") {
      return (
        <a
          className="post-link"
          href={segment.url}
          key={`${start}-${segment.url}`}
          target="_blank"
          rel="noreferrer"
          onClick={(event) => event.stopPropagation()}
        >
          {segment.text}
        </a>
      );
    }
    return segment.text;
  });
}

function omitTrailingRedirectLink(text: string, enabled: boolean): string {
  return enabled ? text.replace(/(?:\s*https:\/\/t\.co\/[A-Za-z0-9]+)+\s*$/u, "").trimEnd() : text;
}

interface ActionProps {
  actionId: string;
  icon: typeof MessageCircle;
  label: string;
  count: number;
  active?: boolean;
  disabled?: boolean;
  pending?: boolean;
  onClick?: () => void;
}

function Action({
  actionId,
  icon: Icon,
  label,
  count,
  active = false,
  disabled = false,
  pending = false,
  onClick,
}: ActionProps) {
  return (
    <button
      type="button"
      className={`post-action${active ? ` active ${actionId}-active` : ""}${pending ? " pending" : ""}`}
      data-post-action={actionId}
      aria-label={label}
      aria-busy={pending}
      disabled={disabled}
      onClick={onClick}
    >
      <Icon
        aria-hidden="true"
        size={16}
        fill={active && (actionId === "like" || actionId === "bookmark") ? "currentColor" : "none"}
      />
      <span>{compactNumber(count)}</span>
    </button>
  );
}

function compactNumber(value: number): string {
  return new Intl.NumberFormat(undefined, { notation: "compact", maximumFractionDigits: 1 }).format(
    value,
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
