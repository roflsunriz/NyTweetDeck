import type {
  EmbeddedPost,
  PreTranslatedPost,
  TimelineAuthor,
  TimelineMedia,
  TimelinePost,
} from "./timeline";
import { formatRelativeTime } from "./relative-time";

export interface ShareablePost {
  id: string;
  text: string;
  createdAt: string | null;
  author: TimelineAuthor;
  media: Pick<TimelineMedia, "url">[];
  quotedPost: EmbeddedPost | null;
  preTranslated?: PreTranslatedPost | null;
}

export function postShareUrl(post: Pick<TimelinePost, "id" | "author">): string {
  return `https://x.com/${post.author.username}/status/${post.id}`;
}

function authorHandle(author: TimelineAuthor): string {
  const display = author.displayName.trim();
  const username = author.username.trim().replace(/^@/u, "");
  const fallback = author.id.trim();
  const name = display.length > 0 ? display : username.length > 0 ? username : fallback;
  const handle = username.length > 0 ? username : fallback;
  return `${name}@${handle}`;
}

function directMediaLinks(media: Pick<TimelineMedia, "url">[]): string[] {
  const links: string[] = [];
  for (const item of media) {
    const url = item.url.trim();
    if (url.length > 0 && !links.includes(url)) {
      links.push(url);
    }
  }
  return links;
}

export function formatAbsoluteTime(value: string | null, locale: string): string | null {
  if (value === null) {
    return null;
  }
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) {
    return null;
  }
  return date.toLocaleString(locale, {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

function quoteTextLines(text: string): string[] {
  return text.split(/\r?\n/u).map((line) => `> ${line}`);
}

/**
 * X公式のGrokプリ翻訳をそのまま使う。本文がない場合は原文へ戻す。
 */
export function translatedShareBody(
  text: string,
  preTranslated: PreTranslatedPost | null | undefined,
): string {
  const translated = preTranslated?.text.trim() ?? "";
  return translated.length > 0 ? translated : text.trim();
}

/**
 * 共有ボタンの詳細コピー形式を作る。
 * 元ポストのメディアだけを含め、引用先のメディアとURLは含めない。
 * translatedが真の場合は本文と引用本文をGrokプリ翻訳へ置き換える。
 */
export function formatDetailedShare(
  post: ShareablePost,
  locale: string,
  nowMilliseconds = Date.now(),
  options: { translated?: boolean } = {},
): string {
  const lines: string[] = [authorHandle(post.author)];
  const body =
    options.translated === true
      ? translatedShareBody(post.text, post.preTranslated)
      : post.text.trim();
  if (body.length > 0) {
    lines.push(body);
  }
  for (const link of directMediaLinks(post.media)) {
    lines.push(link);
  }
  const absolute = formatAbsoluteTime(post.createdAt, locale);
  const relative = formatRelativeTime(post.createdAt, locale, nowMilliseconds);
  if (absolute !== null && relative !== null) {
    lines.push(`${absolute} (${relative})`);
  } else if (absolute !== null) {
    lines.push(absolute);
  } else if (relative !== null) {
    lines.push(relative);
  }
  const quoted = post.quotedPost;
  if (quoted !== null) {
    lines.push(authorHandle(quoted.author));
    const quotedBody =
      options.translated === true
        ? translatedShareBody(quoted.text, quoted.preTranslated)
        : quoted.text.trim();
    if (quotedBody.length > 0) {
      lines.push(...quoteTextLines(quotedBody));
    }
  }
  lines.push(postShareUrl(post));
  return lines.join("\n");
}
