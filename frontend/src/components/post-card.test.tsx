import { afterEach, describe, expect, mock, test } from "bun:test";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { translate } from "../i18n/translations";
import { defaultDisplayPreferences } from "../model/layout";
import { PostCard, type TimelinePost } from "./post-card";
import { PostTranslationProvider } from "./post-translation-context";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("post actions", () => {
  test("updates like count only after successful web mutation", async () => {
    const urls: string[] = [];
    globalThis.fetch = (async (input) => {
      urls.push(String(input));
      return Response.json({ postId: "100", action: "like" });
    }) as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    const likeButton = screen.getByRole("button", { name: "いいね" });
    expect(likeButton.textContent).toBe("3");
    await user.click(likeButton);
    await waitFor(() => expect(likeButton.textContent).toBe("4"));
    await user.click(likeButton);
    await waitFor(() => expect(likeButton.textContent).toBe("3"));

    expect(urls[0]).toContain("/posts/100/actions/like?accountId=account-1");
    expect(urls[1]).toContain("/posts/100/actions/unlike?accountId=account-1");
  });

  test("keeps state when mutation fails", async () => {
    globalThis.fetch = (async () => new Response(null, { status: 502 })) as unknown as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    const bookmarkButton = screen.getByRole("button", { name: "履歴に保存" });
    await user.click(bookmarkButton);

    await screen.findByText("タイムラインを読み込めませんでした。");
    expect(bookmarkButton.textContent).toBe("4");
  });

  test("opens reply composer and the complete overflow menu", async () => {
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    await user.click(screen.getByRole("button", { name: "返信" }));
    expect(screen.getByRole("heading", { name: "返信" })).toBeDefined();
    await user.click(screen.getByRole("button", { name: "閉じる" }));
    await user.click(screen.getByLabelText("ポストメニュー"));

    expect(screen.getByRole("button", { name: "このポストに興味がない" })).toBeDefined();
    expect(screen.getByRole("link", { name: "コミュニティノートリクエスト" })).toBeDefined();
  });

  test("shows the at-username and hashtags and offers repost and quote choices", async () => {
    const user = userEvent.setup();
    const taggedPost = { ...post(), text: "hello #NyTweetDeck" };
    render(<PostCard post={taggedPost} accountId="account-1" translation={translate("ja")} />);

    expect(screen.getByText("@alice")).toBeDefined();
    expect(screen.queryByText("42")).toBeNull();
    expect(screen.getByText("#NyTweetDeck").classList.contains("hashtag")).toBe(true);
    await user.click(screen.getByLabelText("リポスト"));
    expect(screen.getByRole("button", { name: "リポスト" })).toBeDefined();
    await user.click(screen.getByRole("button", { name: "引用" }));
    expect(screen.getByRole("heading", { name: "引用" })).toBeDefined();
  });

  test("shows only subtle reposter context and renders a web-style quoted post card", async () => {
    const openUser = mock(() => undefined);
    const openQuotedPost = mock(() => undefined);
    const user = userEvent.setup();
    const socialPost: TimelinePost = {
      ...post(),
      id: "250",
      text: "Source post",
      author: {
        id: "25",
        username: "origin",
        displayName: "Original Author",
        avatarUrl: null,
        verified: false,
      },
      repostedBy: {
        id: "30",
        username: "reposter",
        displayName: "Reposter",
        avatarUrl: null,
        verified: false,
      },
      quotedPost: {
        id: "249",
        text: "Quoted source",
        language: "ja",
        createdAt: null,
        author: {
          id: "24",
          username: "quoted",
          displayName: "Quoted Author",
          avatarUrl: null,
          verified: false,
        },
        media: [
          {
            id: "quote-photo",
            type: "photo",
            url: "https://pbs.twimg.com/quote.jpg",
            previewUrl: "https://pbs.twimg.com/quote.jpg",
          },
        ],
      },
    };

    render(
      <PostCard
        post={socialPost}
        accountId="account-1"
        translation={translate("ja")}
        onOpenUser={openUser}
        onOpenQuotedPost={openQuotedPost}
      />,
    );

    expect(screen.getByText("Source post")).toBeDefined();
    expect(screen.getByText("Original Author")).toBeDefined();
    expect(screen.queryByText(/RT @origin/)).toBeNull();
    const repostContext = screen.getByRole("button", { name: "Reposterさんがリポスト" });
    expect(repostContext.classList.contains("repost-context")).toBe(true);
    await user.click(repostContext);
    expect(openUser).toHaveBeenCalledWith("30");
    const quote = screen.getByRole("button", { name: /Quoted source/ });
    expect(quote.classList.contains("quoted-post-card")).toBe(true);
    await user.click(quote);
    expect(openQuotedPost).toHaveBeenCalledWith("249");
  });

  test("honors media preview and video autoplay data settings", () => {
    const mediaPost = {
      ...post(),
      media: [
        {
          id: "video-1",
          type: "video",
          url: "https://video.example/video.mp4",
          previewUrl: "https://video.example/preview.jpg",
        },
      ],
    };
    const first = render(
      <PostCard
        post={mediaPost}
        accountId="account-1"
        translation={translate("ja")}
        display={{ ...defaultDisplayPreferences, mediaPreview: false }}
      />,
    );

    expect(screen.getByRole("link", { name: "メディアを表示" })).toBeDefined();
    expect(first.container.querySelector("video")).toBeNull();
    first.unmount();

    const second = render(
      <PostCard
        post={mediaPost}
        accountId="account-1"
        translation={translate("ja")}
        display={{ ...defaultDisplayPreferences, videoAutoplay: true }}
      />,
    );
    expect(second.container.querySelector("video")?.autoplay).toBe(true);
  });

  test("executes follow from the post menu through the authenticated local API", async () => {
    let requestedUrl = "";
    globalThis.fetch = (async (input) => {
      requestedUrl = String(input);
      return Response.json({ userId: "42", action: "follow" });
    }) as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    await user.click(screen.getByLabelText("ポストメニュー"));
    await user.click(screen.getByRole("button", { name: "フォロー" }));

    await screen.findByRole("button", { name: "フォロー · 完了" });
    expect(requestedUrl).toContain("/api/v1/users/42/actions/follow?accountId=account-1");
  });

  test("adds the post author to a selected list through the web mutation", async () => {
    let requestedUrl = "";
    globalThis.fetch = (async (input) => {
      requestedUrl = String(input);
      return Response.json({ userId: "42", listId: "84", action: "add" });
    }) as typeof fetch;
    const user = userEvent.setup();
    render(<PostCard post={post()} accountId="account-1" translation={translate("ja")} />);

    await user.click(screen.getByLabelText("ポストメニュー"));
    await user.click(screen.getByRole("button", { name: "リストから追加と削除" }));
    await user.type(screen.getByLabelText("リストID"), "84");
    await user.click(screen.getByRole("button", { name: "リストに追加" }));

    await screen.findByRole("button", { name: "リストに追加 · 完了" });
    expect(requestedUrl).toContain("/api/v1/users/42/lists/84/add?accountId=account-1");
  });

  test("opens details by clicking or pressing Enter on the post card surface", async () => {
    const onOpen = mock(() => undefined);
    const user = userEvent.setup();
    render(
      <PostCard
        post={post()}
        accountId="account-1"
        translation={translate("ja")}
        onOpen={onOpen}
      />,
    );
    const card = screen.getByRole("article", { name: "ポストの詳細" });

    await user.click(card);
    card.focus();
    await user.keyboard("{Enter}");

    expect(onOpen).toHaveBeenCalledTimes(2);
  });

  test("opens the internal user profile from the author identity", async () => {
    const openUser = mock(() => undefined);
    const user = userEvent.setup();
    render(
      <PostCard
        post={post()}
        accountId="account-1"
        translation={translate("ja")}
        onOpenUser={openUser}
      />,
    );

    await user.click(screen.getByRole("button", { name: /Alice/ }));

    expect(openUser).toHaveBeenCalledWith("42");
  });

  test("automatically translates a foreign-language post and toggles original and global state", async () => {
    globalThis.fetch = (async (input) => {
      if (String(input).includes("/translation?")) {
        return Response.json({
          postId: "900",
          sourceLanguage: "en",
          targetLanguage: "ja",
          text: "自動翻訳された本文",
          provider: "X",
        });
      }
      return Response.json({ completed: true });
    }) as typeof fetch;
    const user = userEvent.setup();

    function Harness() {
      const [enabled, setEnabled] = useState(true);
      return (
        <PostTranslationProvider
          value={{ locale: "ja", autoTranslatePosts: enabled, setAutoTranslatePosts: setEnabled }}
        >
          <PostCard
            post={{ ...post(), id: "900", language: "en", text: "Original post" }}
            accountId="account-1"
            translation={translate("ja")}
          />
        </PostTranslationProvider>
      );
    }

    render(<Harness />);

    expect(await screen.findByText("自動翻訳された本文")).toBeDefined();
    expect(screen.getByText("Xによる自動翻訳")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "原文を表示" }));
    expect(screen.getByText("Original post")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "翻訳を表示" }));
    expect(screen.getByText("自動翻訳された本文")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "すべてのカラムで自動翻訳をオフにする" }));
    expect(screen.getByText("Original post")).toBeDefined();
    expect(
      screen.getByRole("button", { name: "すべてのカラムで自動翻訳をオンにする" }),
    ).toBeDefined();
  });

  test("applies the gear setting to every post and retries a failed translation", async () => {
    const attempts = new Map<string, number>();
    globalThis.fetch = (async (input) => {
      const url = String(input);
      const postId = url.includes("/911/") ? "911" : "910";
      const count = (attempts.get(postId) ?? 0) + 1;
      attempts.set(postId, count);
      if (postId === "910" && count === 1) return new Response(null, { status: 502 });
      return Response.json({
        postId,
        sourceLanguage: "en",
        targetLanguage: "ja",
        text: `翻訳 ${postId}`,
        provider: "X",
      });
    }) as typeof fetch;
    const user = userEvent.setup();

    function Harness() {
      const [enabled, setEnabled] = useState(true);
      return (
        <PostTranslationProvider
          value={{ locale: "ja", autoTranslatePosts: enabled, setAutoTranslatePosts: setEnabled }}
        >
          <PostCard
            post={{ ...post(), id: "910", language: "en", text: "Original 910" }}
            accountId="account-1"
            translation={translate("ja")}
          />
          <PostCard
            post={{ ...post(), id: "911", language: "en", text: "Original 911" }}
            accountId="account-1"
            translation={translate("ja")}
          />
        </PostTranslationProvider>
      );
    }

    render(<Harness />);

    expect(await screen.findByText("翻訳できませんでした。原文を表示しています。")).toBeDefined();
    expect(await screen.findByText("翻訳 911")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "再試行" }));
    expect(await screen.findByText("翻訳 910")).toBeDefined();
    await user.click(
      screen.getAllByRole("button", {
        name: "すべてのカラムで自動翻訳をオフにする",
      })[0] as HTMLButtonElement,
    );
    expect(screen.getByText("Original 910")).toBeDefined();
    expect(screen.getByText("Original 911")).toBeDefined();
    expect(
      screen.getAllByRole("button", {
        name: "すべてのカラムで自動翻訳をオンにする",
      }),
    ).toHaveLength(2);
  });
});

function post(): TimelinePost {
  return {
    id: "100",
    text: "post",
    language: "ja",
    createdAt: null,
    author: { id: "42", username: "alice", displayName: "Alice", avatarUrl: null, verified: false },
    repostedBy: null,
    replyCount: 1,
    repostCount: 2,
    quoteCount: 0,
    likeCount: 3,
    bookmarkCount: 4,
    viewCount: 5,
    liked: false,
    reposted: false,
    bookmarked: false,
    quotedPost: null,
    media: [],
  };
}
