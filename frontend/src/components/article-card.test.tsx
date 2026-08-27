import { afterEach, expect, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { ArticleCard } from "./article-card";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

test("loads the complete article from post details when the timeline only has a preview", async () => {
  globalThis.fetch = (async () =>
    Response.json({
      post: {
        article: {
          id: "901",
          title: "記事タイトル",
          previewText: "タイムラインの概要",
          body: "詳細取得で返された記事全文。",
          coverImageUrl: null,
          url: "https://x.com/i/article/901",
        },
      },
      replies: [],
      nextCursor: null,
    })) as unknown as typeof fetch;
  const user = userEvent.setup();

  render(
    <ArticleCard
      article={{
        id: "901",
        title: "記事タイトル",
        previewText: "タイムラインの概要",
        body: null,
        coverImageUrl: null,
        url: "https://x.com/i/article/901",
      }}
      postId="900"
      accountId="account-1"
      translation={translate("ja")}
    />,
  );
  await user.click(screen.getByRole("button", { name: "記事を読む: 記事タイトル" }));

  expect(await screen.findByText("詳細取得で返された記事全文。")).toBeDefined();
});
