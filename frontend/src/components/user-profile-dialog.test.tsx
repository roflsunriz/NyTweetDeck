import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { UserProfileDialog } from "./user-profile-dialog";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("internal user profile", () => {
  test("shows profile details, switches tabs, and filters media", async () => {
    globalThis.fetch = (async (input: Parameters<typeof fetch>[0]) => {
      const url = String(input);
      if (!url.includes("/timeline")) {
        return Response.json({
          id: "42",
          username: "alice",
          displayName: "Alice",
          description: "Profile description",
          avatarUrl: "https://pbs.twimg.com/alice.jpg",
          bannerUrl: "https://pbs.twimg.com/banner.jpg",
          createdAt: "2020-01-02T00:00:00Z",
          location: "Tokyo",
          website: "https://example.com",
          followingCount: 10,
          followerCount: 20,
          mutualFollowerCount: 1,
          mutualFollowers: [
            {
              id: "7",
              username: "bob",
              displayName: "Bob",
              avatarUrl: "https://pbs.twimg.com/bob.jpg",
            },
          ],
          verified: true,
          following: false,
          followsYou: true,
        });
      }
      return Response.json({
        posts: [post("photo", "photo"), post("video", "video")],
        nextCursor: null,
      });
    }) as unknown as typeof fetch;
    const user = userEvent.setup();
    render(
      <UserProfileDialog
        userId="42"
        accountId="account-1"
        translation={translate("ja")}
        onClose={() => undefined}
      />,
    );

    expect(await screen.findByText("Profile description")).toBeDefined();
    expect(screen.getByText("@alice", { selector: ".profile-identity p" })).toBeDefined();
    expect(screen.queryByText("ユーザーID: 42")).toBeNull();
    expect(screen.getByText("20", { selector: ".profile-counts strong" })).toBeDefined();
    await user.click(screen.getByRole("tab", { name: "メディア" }));
    await waitFor(() => expect(screen.getByText("photo")).toBeDefined());
    await user.click(screen.getByRole("button", { name: "画像" }));
    expect(screen.getByText("photo")).toBeDefined();
    expect(screen.queryByText("video")).toBeNull();
  });
});

function post(id: string, type: "photo" | "video") {
  return {
    id,
    text: id,
    language: "ja",
    createdAt: "2026-08-26T00:00:00Z",
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
    quotedPost: null,
    media: [
      {
        id,
        type,
        url: `https://pbs.twimg.com/${id}`,
        previewUrl: `https://pbs.twimg.com/${id}-preview`,
      },
    ],
  };
}
