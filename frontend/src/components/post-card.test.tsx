import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
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
