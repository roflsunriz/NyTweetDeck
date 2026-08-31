export type PostTextSegment =
  | { kind: "text" | "hashtag"; text: string }
  | { kind: "url"; text: string; url: string };

const TOKEN_PATTERN = /https?:\/\/[^\s<>"'。、！？]+|#[\p{L}\p{N}_]+/giu;
const TRAILING_PUNCTUATION = new Set([".", ",", "!", "?", ":", ";", "。", "、", "！", "？"]);
const BRACKETS = [
  ["(", ")"],
  ["[", "]"],
  ["{", "}"],
] as const;

export function postTextSegments(value: string): PostTextSegment[] {
  const segments: PostTextSegment[] = [];
  let cursor = 0;
  for (const match of value.matchAll(TOKEN_PATTERN)) {
    const start = match.index;
    const token = match[0];
    if (start > cursor) appendText(segments, value.slice(cursor, start));
    if (token.startsWith("#")) {
      segments.push({ kind: "hashtag", text: token });
    } else {
      const link = trimUrlEnd(token);
      if (isSafeHttpUrl(link)) {
        segments.push({ kind: "url", text: link, url: link });
        appendText(segments, token.slice(link.length));
      } else {
        appendText(segments, token);
      }
    }
    cursor = start + token.length;
  }
  appendText(segments, value.slice(cursor));
  return segments;
}

function appendText(segments: PostTextSegment[], value: string): void {
  if (value.length === 0) return;
  const last = segments.at(-1);
  if (last?.kind === "text") {
    last.text += value;
  } else {
    segments.push({ kind: "text", text: value });
  }
}

function trimUrlEnd(value: string): string {
  let end = value.length;
  while (end > 0) {
    const final = value[end - 1];
    if (final !== undefined && TRAILING_PUNCTUATION.has(final)) {
      end -= 1;
      continue;
    }
    const unmatchedClosing = BRACKETS.some(([opening, closing]) => {
      if (final !== closing) return false;
      const candidate = value.slice(0, end);
      return count(candidate, closing) > count(candidate, opening);
    });
    if (unmatchedClosing) {
      end -= 1;
      continue;
    }
    break;
  }
  return value.slice(0, end);
}

function count(value: string, token: string): number {
  return [...value].filter((character) => character === token).length;
}

function isSafeHttpUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return (url.protocol === "http:" || url.protocol === "https:") && url.hostname.length > 0;
  } catch {
    return false;
  }
}
