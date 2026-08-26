import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
import { translate } from "../i18n/translations";
import { TrendsColumn } from "./trends-column";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("trends column", () => {
  test("renders Explore trend names and metadata instead of discarding them as non-posts", async () => {
    globalThis.fetch = (async () =>
      Response.json({
        trends: [
          {
            name: "#NyTweetDeck",
            description: "1,234 posts",
            rank: "1",
            url: "https://x.com/search?q=NyTweetDeck",
            domainContext: "Technology",
            metaDescription: "Trending now",
          },
        ],
        nextCursor: null,
      })) as unknown as typeof fetch;

    render(<TrendsColumn accountId="account-1" translation={translate("ja")} />);

    expect(await screen.findByRole("link", { name: /#NyTweetDeck/ })).toBeDefined();
    expect(screen.getByText("Technology")).toBeDefined();
    expect(screen.getByText("1,234 posts")).toBeDefined();
  });
});
