import { afterEach, expect, mock, test } from "bun:test";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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

test("resumes an unfinished reply page after navigating away and Back", async () => {
  let finishPage: ((value: Response) => void) | undefined;
  const page = new Promise<Response>((resolve) => {
    finishPage = resolve;
  });
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes("cursor=next")) return page;
    const id = url.includes("/posts/901?") ? "901" : "900";
    return Response.json({
      post: timelinePost(id, `focal ${id}`),
      replies: [timelinePost("901", "child")],
      nextCursor: id === "900" ? "next" : null,
    });
  }) as typeof fetch;
  const { container } = render(
    <PostDetailDialog
      postId="900"
      accountId="account-1"
      translation={translate("ja")}
      onClose={() => undefined}
    />,
  );
  const user = userEvent.setup();
  await screen.findByText("child");
  const load = container.querySelector<HTMLButtonElement>(".detail-load-more button");
  if (load === null) throw new Error("Missing pagination");
  await user.click(load);
  await user.click(screen.getByText("child"));
  await screen.findByText("focal 901");
  await user.keyboard("{Escape}");
  await screen.findByText("focal 900");
  const retry = container.querySelector<HTMLButtonElement>(".detail-load-more button");
  if (retry === null) throw new Error("Missing resumed pagination");
  await user.click(retry);
  await act(async () => {
    finishPage?.(
      Response.json({
        post: timelinePost("900", "focal 900"),
        replies: [timelinePost("902", "resumed page reply")],
        nextCursor: null,
      }),
    );
  });
  expect(await screen.findByText("resumed page reply")).toBeDefined();
});

test("does not reset nested navigation when the root post updates, and resumes unfinished detail on Back", async () => {
  const root = {
    ...timelinePost("900", "root loading"),
    quotedPost: timelinePost("800", "quoted ancestor"),
  };
  let resolveRoot: ((response: Response) => void) | undefined;
  const rootResponse = new Promise<Response>((resolve) => {
    resolveRoot = resolve;
  });
  globalThis.fetch = (async (input: RequestInfo | URL) =>
    String(input).includes("/posts/900?")
      ? rootResponse
      : Response.json({
          post: timelinePost("800", "ancestor loaded"),
          replies: [],
          nextCursor: null,
        })) as typeof fetch;
  const props = {
    postId: "900",
    accountId: "account-1",
    translation: translate("ja"),
    onClose: () => undefined,
  };
  const { container, rerender } = render(<PostDetailDialog {...props} initialPost={root} />);
  const user = userEvent.setup();
  await screen.findByText("root loading");
  const quoted = container.querySelector<HTMLButtonElement>(".quoted-post-open");
  if (quoted === null) throw new Error("Missing quote");
  await user.click(quoted);
  await screen.findByText("ancestor loaded");
  rerender(<PostDetailDialog {...props} initialPost={{ ...root, likeCount: 10 }} />);
  expect(
    container.querySelector("[data-detail-post-id]")?.getAttribute("data-detail-post-id"),
  ).toBe("800");
  await user.keyboard("{Escape}");
  await screen.findByText("root loading");
  await act(async () => {
    resolveRoot?.(
      Response.json({
        post: root,
        replies: [timelinePost("901", "resumed reply")],
        nextCursor: null,
      }),
    );
  });
  expect(await screen.findByText("resumed reply")).toBeDefined();
});

test("follows replies repeatedly and restores each detail and scroll through Back and Escape", async () => {
  const closed = mock(() => undefined);
  const requests: string[] = [];
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const url = String(input);
    requests.push(url);
    const id = url.match(/posts\/(\d+)/u)?.[1] ?? "900";
    return Response.json({
      post: timelinePost(id, `focal ${id}`),
      contextPosts: [],
      replies: [timelinePost(String(Number(id) + 1), `child ${Number(id) + 1}`)],
      nextCursor: null,
    });
  }) as typeof fetch;
  const user = userEvent.setup();
  const { container } = render(
    <PostDetailDialog
      postId="900"
      accountId="account-1"
      translation={translate("ja")}
      onClose={closed}
    />,
  );
  await screen.findByText("child 901");
  const panel = container.querySelector<HTMLElement>(".modal-panel");
  if (panel === null) throw new Error("Missing detail panel");
  panel.scrollTop = 200;
  await user.click(screen.getByText("child 901"));
  await screen.findByText("child 902");
  expect(window.location.hash).toBe("#/post/901");
  panel.scrollTop = 300;
  const card = container.querySelector<HTMLElement>('[data-reply-thread-id="902"] .post-card');
  if (card === null) throw new Error("Missing reply card");
  card.focus();
  await user.keyboard("{Enter}");
  await screen.findByText("child 903");
  expect(window.location.hash).toBe("#/post/902");
  await user.click(screen.getByText("child 903"));
  await screen.findByText("child 904");
  await user.keyboard("{Escape}");
  await screen.findByText("child 903");
  await act(async () => {
    window.history.back();
    await new Promise((resolve) => setTimeout(resolve, 10));
  });
  await screen.findByText("child 902");
  expect(panel.scrollTop).toBe(300);
  await user.keyboard("{Escape}");
  await screen.findByText("child 901");
  expect(panel.scrollTop).toBe(200);
  expect(closed).not.toHaveBeenCalled();
  expect(requests.filter((url) => /\/posts\/\d+\?/u.test(url)).length).toBe(4);
  const reply = container.querySelector<HTMLElement>(
    '[data-reply-thread-id="901"] [data-post-action="reply"]',
  );
  if (reply === null) throw new Error("Missing reply action");
  await user.click(reply);
  expect(container.querySelector(".composer-form")).not.toBeNull();
  await user.keyboard("{Escape}");
  await waitFor(() => expect(container.querySelector(".composer-form")).toBeNull());
  expect(
    container.querySelector("[data-detail-post-id]")?.getAttribute("data-detail-post-id"),
  ).toBe("900");
  expect(closed).not.toHaveBeenCalled();
});

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
  const detailRegion = screen.getByRole("region", { name: "ポストの詳細" });
  expect(detailRegion.getAttribute("aria-modal")).toBeNull();
  expect(detailRegion.getAttribute("data-presentation")).toBe("route");
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

test("shows the known focal post immediately while replies load in the background", async () => {
  let finishRequest: ((response: Response) => void) | undefined;
  globalThis.fetch = (async () =>
    new Promise<Response>((resolve) => {
      finishRequest = resolve;
    })) as unknown as typeof fetch;
  const initialPost = timelinePost("850", "known focal post");

  render(
    <PostDetailDialog
      postId="850"
      initialPost={initialPost}
      accountId="account-1"
      translation={translate("ja")}
      onClose={() => undefined}
    />,
  );

  expect(screen.getByText("known focal post")).toBeDefined();
  expect(screen.getByText("読み込み中…", { selector: ".detail-background-loading" })).toBeDefined();

  finishRequest?.(
    Response.json({
      post: initialPost,
      replies: [timelinePost("851", "loaded reply")],
      nextCursor: null,
    }),
  );
  await screen.findByText("loaded reply");
  expect(screen.queryByText("読み込み中…", { selector: ".detail-background-loading" })).toBeNull();
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

test("shows reply ancestors and loads older replies from a timeline detail", async () => {
  let requests = 0;
  globalThis.fetch = (async () => {
    requests += 1;
    return requests === 1
      ? Response.json({
          post: timelinePost("900", "focal post"),
          contextPosts: [timelinePost("899", "previous reply")],
          replies: [timelinePost("901", "first reply")],
          nextCursor: "older",
        })
      : Response.json({
          post: timelinePost("900", "focal post"),
          replies: [timelinePost("902", "older reply")],
          nextCursor: null,
        });
  }) as unknown as typeof fetch;
  const user = userEvent.setup();

  render(
    <PostDetailDialog
      postId="900"
      accountId="account-1"
      translation={translate("ja")}
      onClose={() => undefined}
    />,
  );

  expect(await screen.findByText("previous reply")).toBeDefined();
  expect(screen.getByText("first reply")).toBeDefined();
  await user.click(screen.getByRole("button", { name: "さらに返信を読み込む" }));
  expect(await screen.findByText("older reply")).toBeDefined();
  expect(requests).toBe(2);
  expect(screen.queryByRole("button", { name: "さらに返信を読み込む" })).toBeNull();
});

for (const replyCount of [0, 3]) {
  test(`finishes ${replyCount}-reply pages when the cursor repeats`, async () => {
    let requests = 0;
    globalThis.fetch = (async () => {
      requests += 1;
      return Response.json({
        post: timelinePost("900", "focal post"),
        replies: Array.from({ length: replyCount }, (_, i) =>
          timelinePost(String(901 + i), `reply ${i}`),
        ),
        nextCursor: "older",
      });
    }) as unknown as typeof fetch;
    const { container } = render(
      <PostDetailDialog
        postId="900"
        accountId="account-1"
        translation={translate("ja")}
        onClose={() => undefined}
      />,
    );
    await screen.findByText("focal post");
    await userEvent
      .setup()
      .click(container.querySelector(".detail-load-more button") as HTMLButtonElement);
    await waitFor(() => expect(container.querySelector(".detail-load-more")).toBeNull());
    expect(requests).toBe(2);
    expect(container.querySelectorAll("[data-reply-thread-id]").length).toBe(replyCount);
  });
}

test("continues empty pages with new cursors and permits retry after a failure", async () => {
  const cursors: (string | null)[] = [];
  globalThis.fetch = (async (input: Parameters<typeof fetch>[0]) => {
    const cursor = new URL(String(input), "http://localhost").searchParams.get("cursor");
    cursors.push(cursor);
    if (cursors.length === 2) return new Response(null, { status: 503 });
    return Response.json({
      post: timelinePost("900", "focal post"),
      replies: cursor === "second" ? [timelinePost("901", "last reply")] : [],
      nextCursor: cursor === null ? "first" : cursor === "first" ? "second" : null,
    });
  }) as unknown as typeof fetch;
  const { container } = render(
    <PostDetailDialog
      postId="900"
      accountId="account-1"
      translation={translate("ja")}
      onClose={() => undefined}
    />,
  );
  await screen.findByText("focal post");
  const clickMore = () =>
    fireEvent.click(container.querySelector(".detail-load-more button") as HTMLButtonElement);
  clickMore();
  await waitFor(() =>
    expect(container.querySelector(".detail-load-more .inline-error")).not.toBeNull(),
  );
  clickMore();
  await waitFor(() =>
    expect(
      (container.querySelector(".detail-load-more button") as HTMLButtonElement).disabled,
    ).toBe(false),
  );
  expect(container.querySelector(".detail-load-more .inline-error")).toBeNull();
  clickMore();
  await screen.findByText("last reply");
  expect(container.querySelector(".detail-load-more")).toBeNull();
  expect(cursors).toEqual([null, "first", "first", "second"]);
});

for (const failure of [false, true]) {
  test(`ignores old page ${failure ? "failure" : "response"} after switching posts`, async () => {
    let completeOld: (response: Response) => void = () => {
      throw new Error("missing old request");
    };
    globalThis.fetch = (async (input: Parameters<typeof fetch>[0]) => {
      const url = new URL(String(input), "http://localhost");
      if (url.searchParams.has("cursor"))
        return new Promise<Response>((resolve) => {
          completeOld = resolve;
        });
      const id = url.pathname.endsWith("900") ? "900" : "950";
      return Response.json({
        post: timelinePost(id, `post ${id}`),
        replies: [],
        nextCursor: "older",
      });
    }) as unknown as typeof fetch;
    const props = {
      accountId: "account-1",
      translation: translate("ja"),
      onClose: () => undefined,
    };
    const { container, rerender } = render(<PostDetailDialog {...props} postId="900" />);
    await screen.findByText("post 900");
    fireEvent.click(container.querySelector(".detail-load-more button") as HTMLButtonElement);
    rerender(<PostDetailDialog {...props} postId="950" />);
    await screen.findByText("post 950");
    completeOld(
      failure
        ? new Response(null, { status: 503 })
        : Response.json({
            post: timelinePost("900", "old post"),
            replies: [timelinePost("901", "obsolete reply")],
            nextCursor: null,
          }),
    );
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(screen.queryByText("obsolete reply")).toBeNull();
    expect(container.querySelector(".inline-error")).toBeNull();
    expect(container.querySelector(".detail-load-more button")).not.toBeNull();
  });
}

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
