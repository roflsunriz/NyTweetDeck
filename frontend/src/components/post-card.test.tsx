import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { defaultDisplayPreferences } from "../model/layout";
import { PostCard, type TimelinePost } from "./post-card";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("post actions", () => {
  test("updates like count only after successful Android mutation", async () => {
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

  test("shows user id and hashtags and offers repost and quote choices", async () => {
    const user = userEvent.setup();
    const taggedPost = { ...post(), text: "hello #NyTweetDeck" };
    render(<PostCard post={taggedPost} accountId="account-1" translation={translate("ja")} />);

    expect(screen.getByText("ユーザーID: 42")).toBeDefined();
    expect(screen.getByText("#NyTweetDeck").classList.contains("hashtag")).toBe(true);
    await user.click(screen.getByLabelText("リポスト"));
    expect(screen.getByRole("button", { name: "リポスト" })).toBeDefined();
    await user.click(screen.getByRole("button", { name: "引用" }));
    expect(screen.getByRole("heading", { name: "引用" })).toBeDefined();
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
});

function post(): TimelinePost {
  return {
    id: "100",
    text: "post",
    createdAt: null,
    author: { id: "42", username: "alice", displayName: "Alice", avatarUrl: null, verified: false },
    replyCount: 1,
    repostCount: 2,
    quoteCount: 0,
    likeCount: 3,
    bookmarkCount: 4,
    viewCount: 5,
    liked: false,
    reposted: false,
    bookmarked: false,
    media: [],
  };
}
