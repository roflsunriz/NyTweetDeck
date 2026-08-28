import { afterEach, expect, mock, test } from "bun:test";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { translate } from "../i18n/translations";
import type { ReplySort } from "../model/layout";
import type { TimelinePost } from "./post-card";
import { PostDetailDialog } from "./post-detail-dialog";
import { PostTranslationProvider } from "./post-translation-context";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

test("opens a detail image viewer with pan, zoom, and one-level Escape navigation", async () => {
  const onClose = mock(() => undefined);
  const detailPost: TimelinePost = {
    id: "800",
    text: "画像ポスト",
    language: "ja",
    createdAt: null,
    author: {
      id: "42",
      username: "alice",
      displayName: "Alice",
      avatarUrl: null,
      verified: false,
    },
    repostedBy: null,
    replyCount: 0,
    repostCount: 0,
    quoteCount: 0,
    likeCount: 0,
    bookmarkCount: 0,
    viewCount: 0,
    liked: false,
    reposted: false,
    bookmarked: false,
    replyToPostId: null,
    replyToUsername: null,
    quotedPost: null,
    media: [
      {
        id: "photo-800",
        type: "photo",
        url: "https://pbs.twimg.com/media/photo800.jpg?format=jpg&name=small",
        previewUrl: "https://pbs.twimg.com/media/photo800.jpg?format=jpg&name=small",
      },
    ],
  };
  globalThis.fetch = (async () =>
    Response.json({ post: detailPost, replies: [], nextCursor: null })) as unknown as typeof fetch;
  const user = userEvent.setup();

  render(
    <PostDetailDialog
      postId="800"
      accountId="account-1"
      translation={translate("ja")}
      onClose={onClose}
    />,
  );
  await screen.findByText("画像ポスト");
  await user.click(screen.getByRole("button", { name: "画像をフルサイズで表示" }));

  const viewer = screen.getByRole("dialog", { name: "画像をフルサイズで表示" });
  const fullImage = viewer.querySelector("img");
  expect(fullImage?.getAttribute("src")).toContain("name=orig");
  const viewport = viewer.querySelector(".image-viewer-viewport");
  if (!(viewport instanceof HTMLElement)) throw new Error("画像ビュー領域がありません。");
  fireEvent.wheel(viewport, { deltaY: -200, clientX: 100, clientY: 100 });
  await waitFor(() => expect(Number(viewport.dataset.zoom)).toBeGreaterThan(1));
  fireEvent(viewport, pointerEvent("pointerdown", 50, 50));
  fireEvent(viewport, pointerEvent("pointermove", 80, 75));
  expect(fullImage?.getAttribute("style")).toContain("translate(30px, 25px)");

  await user.keyboard("{Escape}");
  expect(screen.queryByRole("dialog", { name: "画像をフルサイズで表示" })).toBeNull();
  expect(screen.getByRole("heading", { name: "ポストの詳細" })).toBeDefined();
  expect(onClose).not.toHaveBeenCalled();
  await user.keyboard("{Escape}");
  expect(onClose).toHaveBeenCalledTimes(1);
});

test("uses X reply ranking modes and folds replies X classifies as possible spam", async () => {
  const urls: string[] = [];
  globalThis.fetch = (async (input) => {
    const url = String(input);
    urls.push(url);
    const replySort = new URL(url, "http://localhost").searchParams.get("replySort") ?? "relevance";
    return Response.json({
      post: timelinePost("900", "focal post"),
      replies: [
        timelinePost("901", `${replySort} regular reply`, "HighQuality"),
        timelinePost("902", "low quality reply", "LowQuality"),
        timelinePost("903", "abusive quality reply", "AbusiveQuality"),
      ],
      nextCursor: null,
    });
  }) as typeof fetch;
  const user = userEvent.setup();

  render(<ReplyPreferenceHarness />);

  await screen.findByText("relevance regular reply");
  expect(screen.queryByText("low quality reply")).toBeNull();
  const spamToggle = screen.getByRole("button", {
    name: "2件のスパムの可能性のあるリプライを表示",
  });
  expect(spamToggle.getAttribute("aria-expanded")).toBe("false");

  await user.selectOptions(screen.getByLabelText("返信の並び順"), "recency");

  await screen.findByText("recency regular reply");
  expect(screen.getByTestId("remembered-reply-sort").textContent).toBe("recency");
  expect(screen.queryByText("low quality reply")).toBeNull();
  expect(urls.at(-1)).toContain("replySort=recency");

  await user.click(screen.getByRole("button", { name: "2件のスパムの可能性のあるリプライを表示" }));

  expect(await screen.findByText("low quality reply")).toBeDefined();
  expect(screen.getByText("abusive quality reply")).toBeDefined();
  expect(
    screen.getByRole("button", { name: "2件のスパムの可能性のあるリプライを折り畳む" }),
  ).toBeDefined();

  await user.selectOptions(screen.getByLabelText("返信の並び順"), "likes");

  await screen.findByText("likes regular reply");
  expect(screen.queryByText("low quality reply")).toBeNull();
  expect(urls.at(-1)).toContain("replySort=likes");
});

function ReplyPreferenceHarness() {
  const [replySort, setReplySort] = useState<ReplySort>("relevance");
  return (
    <PostTranslationProvider
      value={{
        locale: "ja",
        autoTranslatePosts: true,
        setAutoTranslatePosts: () => undefined,
        replySort,
        setReplySort,
      }}
    >
      <span data-testid="remembered-reply-sort">{replySort}</span>
      <PostDetailDialog
        postId="900"
        accountId="account-1"
        translation={translate("ja")}
        onClose={() => undefined}
      />
    </PostTranslationProvider>
  );
}

function timelinePost(
  id: string,
  text: string,
  conversationSection: TimelinePost["conversationSection"] = null,
): TimelinePost {
  return {
    id,
    text,
    language: "ja",
    createdAt: null,
    author: {
      id: `author-${id}`,
      username: `author_${id}`,
      displayName: `Author ${id}`,
      avatarUrl: null,
      verified: false,
    },
    repostedBy: null,
    conversationSection,
    replyCount: 0,
    repostCount: 0,
    quoteCount: 0,
    likeCount: 0,
    bookmarkCount: 0,
    viewCount: 0,
    liked: false,
    reposted: false,
    bookmarked: false,
    replyToPostId: null,
    replyToUsername: null,
    quotedPost: null,
    media: [],
  };
}

function pointerEvent(type: string, clientX: number, clientY: number): Event {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperties(event, {
    pointerId: { value: 1 },
    button: { value: 0 },
    clientX: { value: clientX },
    clientY: { value: clientY },
  });
  return event;
}
