import type {
  EmbeddedPost,
  PreTranslatedPost,
  TimelineAuthor,
  TimelineMedia,
  TimelinePost,
} from "./timeline";
import type { Locale } from "./layout";
import {
  hasTranslatableText,
  loadPostTranslation,
  shouldTranslatePost,
  translationTargetsLocale,
} from "./post-translation";
import { formatRelativeTime } from "./relative-time";

const shareTranslationTimeoutMilliseconds = 30_000;

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

export interface ShareTranslationScope {
  accountId: string;
  postId: string;
  text: string;
  language: string | null;
  preTranslated?: PreTranslatedPost | null;
}

/**
 * 翻訳版コピー用の本文を解決する。プリ翻訳→メモリ→ライブ翻訳の順で、
 * 取得できない場合は原文へ戻す。ライブ翻訳は既存のプールと速度制限を共有し、
 * 共有操作を長く待たせないよう打ち切っても原文でコピーする。
 */
export async function resolveTranslatedShareBody(
  scope: ShareTranslationScope,
  translationLocale: Locale,
  options: { timeoutMilliseconds?: number } = {},
): Promise<string> {
  const original = scope.text.trim();
  if (!hasTranslatableText(scope.text)) {
    return original;
  }
  const preTranslated =
    scope.preTranslated !== undefined &&
    scope.preTranslated !== null &&
    translationTargetsLocale(scope.preTranslated.targetLanguage, translationLocale)
      ? scope.preTranslated
      : null;
  if (preTranslated !== null) {
    const translated = preTranslated.text.trim();
    if (translated.length > 0) {
      return translated;
    }
  }
  if (scope.language === null || !shouldTranslatePost(scope.language, translationLocale)) {
    return original;
  }
  const timeout = options.timeoutMilliseconds ?? shareTranslationTimeoutMilliseconds;
  try {
    const result = await raceWithTimeout(
      loadPostTranslation({
        accountId: scope.accountId,
        postId: scope.postId,
        sourceLanguage: scope.language,
        targetLanguage: translationLocale,
        text: scope.text,
      }),
      timeout,
    );
    return result.text;
  } catch {
    return original;
  }
}

function raceWithTimeout<T>(operation: Promise<T>, timeoutMilliseconds: number): Promise<T> {
  let timer: ReturnType<typeof globalThis.setTimeout> | undefined;
  const timeout = new Promise<T>((_, reject) => {
    timer = globalThis.setTimeout(() => {
      reject(new Error("Share translation timed out"));
    }, timeoutMilliseconds);
  });
  return Promise.race([operation, timeout]).finally(() => {
    if (timer !== undefined) {
      globalThis.clearTimeout(timer);
    }
  });
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
  const body =
    options.translated === true
      ? translatedShareBody(post.text, post.preTranslated)
      : post.text.trim();
  const quoted = post.quotedPost;
  const quotedBody =
    quoted === null
      ? null
      : options.translated === true
        ? translatedShareBody(quoted.text, quoted.preTranslated)
        : quoted.text.trim();
  return buildShareLines({
    authorLabel: authorHandle(post.author),
    body,
    mediaLinks: directMediaLinks(post.media),
    absolute: formatAbsoluteTime(post.createdAt, locale),
    relative: formatRelativeTime(post.createdAt, locale, nowMilliseconds),
    quotedAuthorLabel: quoted === null ? null : authorHandle(quoted.author),
    quotedBody,
    postUrl: postShareUrl(post),
  });
}

/**
 * 翻訳版コピーの全文を作る。プリ翻訳がなければライブ翻訳へ進み、
 * 取得できない本文は原文のまま残す。
 */
export async function formatTranslatedDetailedShare(
  accountId: string,
  post: ShareablePost & { language: string | null },
  locale: string,
  translationLocale: Locale,
  nowMilliseconds = Date.now(),
  options: { timeoutMilliseconds?: number } = {},
): Promise<string> {
  const quoted = post.quotedPost;
  const [body, quotedBody] = await Promise.all([
    resolveTranslatedShareBody(
      {
        accountId,
        postId: post.id,
        text: post.text,
        language: post.language,
        preTranslated: post.preTranslated,
      },
      translationLocale,
      options,
    ),
    quoted === null
      ? Promise.resolve<string | null>(null)
      : resolveTranslatedShareBody(
          {
            accountId,
            postId: quoted.id,
            text: quoted.text,
            language: quoted.language,
            preTranslated: quoted.preTranslated,
          },
          translationLocale,
          options,
        ),
  ]);
  return buildShareLines({
    authorLabel: authorHandle(post.author),
    body,
    mediaLinks: directMediaLinks(post.media),
    absolute: formatAbsoluteTime(post.createdAt, locale),
    relative: formatRelativeTime(post.createdAt, locale, nowMilliseconds),
    quotedAuthorLabel: quoted === null ? null : authorHandle(quoted.author),
    quotedBody,
    postUrl: postShareUrl(post),
  });
}

function buildShareLines(input: {
  authorLabel: string;
  body: string;
  mediaLinks: string[];
  absolute: string | null;
  relative: string | null;
  quotedAuthorLabel: string | null;
  quotedBody: string | null;
  postUrl: string;
}): string {
  const lines: string[] = [input.authorLabel];
  if (input.body.trim().length > 0) {
    lines.push(input.body.trim());
  }
  for (const link of input.mediaLinks) {
    lines.push(link);
  }
  if (input.absolute !== null && input.relative !== null) {
    lines.push(`${input.absolute} (${input.relative})`);
  } else if (input.absolute !== null) {
    lines.push(input.absolute);
  } else if (input.relative !== null) {
    lines.push(input.relative);
  }
  if (input.quotedAuthorLabel !== null) {
    lines.push(input.quotedAuthorLabel);
    if (input.quotedBody !== null && input.quotedBody.trim().length > 0) {
      lines.push(...quoteTextLines(input.quotedBody.trim()));
    }
  }
  lines.push(input.postUrl);
  return lines.join("\n");
}
