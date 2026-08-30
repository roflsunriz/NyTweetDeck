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
const originalInnerWidth = window.innerWidth;
const originalDirection = document.documentElement.dir;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
  Object.defineProperty(window, "innerWidth", { configurable: true, value: originalInnerWidth });
  document.documentElement.dir = originalDirection;
});

test("opens a detail image viewer with sub-100% zoom, held-button pan, and one-level Escape navigation", async () => {
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
      {
        id: "photo-801",
        type: "photo",
        url: "https://pbs.twimg.com/media/photo801.jpg?format=jpg&name=small",
        previewUrl: "https://pbs.twimg.com/media/photo801.jpg?format=jpg&name=small",
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
  const firstImageButton = screen.getAllByRole("button", {
    name: "画像をフルサイズで表示",
  })[0];
  if (firstImageButton === undefined) throw new Error("画像を開くボタンがありません。");
  await user.click(firstImageButton);

  const viewer = screen.getByRole("dialog", { name: "画像をフルサイズで表示" });
  const fullImage = viewer.querySelector("img");
  expect(fullImage?.getAttribute("src")).toContain("name=orig");
  const viewport = viewer.querySelector(".image-viewer-viewport");
  if (!(viewport instanceof HTMLElement)) throw new Error("画像ビュー領域がありません。");
  expect(viewport.dataset.imageCount).toBe("2");
  await user.keyboard("{ArrowRight}");
  expect(fullImage?.getAttribute("src")).toContain("photo801.jpg");
  await user.keyboard("{ArrowLeft}");
  expect(fullImage?.getAttribute("src")).toContain("photo800.jpg");
  fireEvent.wheel(viewport, { deltaY: 400, clientX: 100, clientY: 100 });
  await waitFor(() => expect(Number(viewport.dataset.zoom)).toBeLessThan(1));
  await user.click(screen.getByRole("button", { name: "表示位置と倍率をリセット" }));
  await waitFor(() => expect(Number(viewport.dataset.zoom)).toBe(1));
  fireEvent.wheel(viewport, { deltaY: -200, clientX: 100, clientY: 100 });
  await waitFor(() => expect(Number(viewport.dataset.zoom)).toBeGreaterThan(1));
  await user.click(screen.getByRole("button", { name: "表示位置と倍率をリセット" }));
  fireEvent(viewport, pointerEvent("pointerdown", 50, 50, 1));
  fireEvent(viewport, pointerEvent("pointermove", 80, 75, 0));
  expect(fullImage?.getAttribute("style")).toContain("translate(0px, 0px)");
  fireEvent(viewport, pointerEvent("pointerdown", 50, 50, 1));
  fireEvent(viewport, pointerEvent("pointermove", 80, 75, 1));
  expect(fullImage?.getAttribute("style")).toContain("translate(30px, 25px)");
  fireEvent(viewport, pointerEvent("pointermove", 100, 85, 1));
  expect(fullImage?.getAttribute("style")).toContain("translate(50px, 35px)");
  fireEvent(viewport, pointerEvent("pointerup", 100, 85, 0));
  fireEvent(viewport, pointerEvent("pointermove", 130, 110, 1));
  expect(fullImage?.getAttribute("style")).toContain("translate(50px, 35px)");

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
        { ...timelinePost("902", "low quality reply", "LowQuality"), replyToPostId: "901" },
        {
          ...timelinePost("903", "abusive quality reply", "AbusiveQuality"),
          replyToPostId: "902",
        },
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
    document.querySelector('[data-reply-thread-id="902"]')?.getAttribute("data-thread-depth"),
  ).toBe("1");
  expect(
    document.querySelector('[data-reply-thread-id="903"]')?.getAttribute("data-thread-depth"),
  ).toBe("2");
  expect(
    screen.getByRole("button", { name: "2件のスパムの可能性のあるリプライを折り畳む" }),
  ).toBeDefined();

  await user.selectOptions(screen.getByLabelText("返信の並び順"), "likes");

  await screen.findByText("likes regular reply");
  expect(screen.queryByText("low quality reply")).toBeNull();
  expect(urls.at(-1)).toContain("replySort=likes");
});

for (const viewport of [
  { width: 390, locale: "ar" as const, direction: "rtl" as const },
  { width: 1440, locale: "ja" as const, direction: "ltr" as const },
]) {
  test(`renders reply ancestry without translated selectors at ${viewport.width}px ${viewport.direction}`, async () => {
    Object.defineProperty(window, "innerWidth", { configurable: true, value: viewport.width });
    document.documentElement.dir = viewport.direction;
    const replies = [
      { ...timelinePost("B", "reply B"), replyToPostId: "A" },
      { ...timelinePost("E", "reply E"), replyToPostId: "B" },
      { ...timelinePost("F", "reply F"), replyToPostId: "E" },
      { ...timelinePost("C", "reply C"), replyToPostId: "A" },
      { ...timelinePost("D", "reply D"), replyToPostId: "A" },
    ];
    globalThis.fetch = (async () =>
      Response.json({
        post: timelinePost("A", "focal A"),
        replies,
        nextCursor: null,
      })) as unknown as typeof fetch;

    const { container } = render(
      <PostDetailDialog
        postId="A"
        accountId="account-1"
        translation={translate(viewport.locale)}
        onClose={() => undefined}
      />,
    );
    await screen.findByText("reply F");

    const nodes = Array.from(container.querySelectorAll<HTMLElement>("[data-reply-thread-id]"));
    expect(nodes.map((node) => node.dataset.replyThreadId)).toEqual(["B", "E", "F", "C", "D"]);
    expect(threadDomSummary(nodes)).toEqual([
      { id: "B", depth: "0", ancestors: "", last: "false", leaf: "false" },
      { id: "E", depth: "1", ancestors: "B", last: "true", leaf: "false" },
      { id: "F", depth: "2", ancestors: "B,E", last: "true", leaf: "true" },
      { id: "C", depth: "0", ancestors: "", last: "false", leaf: "true" },
      { id: "D", depth: "0", ancestors: "", last: "true", leaf: "true" },
    ]);
    const fNode = nodes[2];
    if (fNode === undefined) throw new Error("F返信がありません。");
    expect(fNode.style.getPropertyValue("--reply-thread-depth")).toBe("2");
    expect(
      Array.from(fNode.querySelectorAll<HTMLElement>("[data-reply-thread-guide]")).map(
        (guide) => guide.dataset.threadContinues,
      ),
    ).toEqual(["true", "false"]);
    expect(fNode.querySelector("[data-reply-thread-branch]")).not.toBeNull();
    expect(document.documentElement.dir).toBe(viewport.direction);
  });
}

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

function pointerEvent(type: string, clientX: number, clientY: number, buttons: number): Event {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperties(event, {
    pointerId: { value: 1 },
    button: { value: 0 },
    buttons: { value: buttons },
    clientX: { value: clientX },
    clientY: { value: clientY },
  });
  return event;
}

function threadDomSummary(nodes: HTMLElement[]) {
  return nodes.map((node) => ({
    id: node.dataset.replyThreadId,
    depth: node.dataset.threadDepth,
    ancestors: node.dataset.threadAncestors,
    last: node.dataset.threadLastSibling,
    leaf: node.dataset.threadLeaf,
  }));
}
