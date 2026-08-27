import { afterEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { filterTrends, TrendsColumn } from "./trends-column";

const originalFetch = globalThis.fetch;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
});

describe("trends column", () => {
  test("filters names and metadata without another request", () => {
    const trends = [
      {
        name: "#NyTweetDeck",
        description: "1,234 posts",
        rank: "1",
        url: "https://x.com/search?q=NyTweetDeck",
        domainContext: "Technology",
        metaDescription: "Trending now",
      },
      {
        name: "Japan",
        description: null,
        rank: "2",
        url: "https://x.com/search?q=Japan",
        domainContext: "News",
        metaDescription: null,
      },
    ];

    expect(filterTrends(trends, " technology ").map((trend) => trend.name)).toEqual([
      "#NyTweetDeck",
    ]);
    expect(filterTrends(trends, "JAPAN").map((trend) => trend.name)).toEqual(["Japan"]);
    expect(filterTrends(trends, "")).toEqual(trends);
  });

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

    let selected = "";
    const user = userEvent.setup();
    render(
      <TrendsColumn
        accountId="account-1"
        translation={translate("ja")}
        onSelect={(query) => {
          selected = query;
        }}
      />,
    );

    const trend = await screen.findByRole("button", { name: /#NyTweetDeck/ });
    await user.click(trend);
    expect(selected).toBe("#NyTweetDeck");
    expect(screen.getByText(/Technology/)).toBeDefined();
    expect(screen.getByText("1,234 posts")).toBeDefined();
    expect(trend.classList.contains("deck-feed-item")).toBe(true);
    expect(trend.getAttribute("data-trend-rank")).toBe("1");
  });

  test("leaves loading state after a stalled request times out and can retry", async () => {
    let calls = 0;
    globalThis.fetch = (async (
      _input: Parameters<typeof fetch>[0],
      init?: Parameters<typeof fetch>[1],
    ) => {
      calls += 1;
      if (calls === 1) {
        return await new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener(
            "abort",
            () => reject(init.signal?.reason ?? new DOMException("Aborted", "AbortError")),
            { once: true },
          );
        });
      }
      return Response.json({
        trends: [
          {
            name: "#Recovered",
            description: null,
            rank: null,
            url: "https://x.com/search?q=Recovered",
            domainContext: null,
            metaDescription: null,
          },
        ],
        nextCursor: null,
      });
    }) as unknown as typeof fetch;
    const user = userEvent.setup();
    render(
      <TrendsColumn
        accountId="account-1"
        translation={translate("ja")}
        requestTimeoutMilliseconds={5}
      />,
    );

    expect(await screen.findByText("トレンドを読み込めませんでした。")).toBeDefined();
    await user.click(screen.getByRole("button", { name: "再試行" }));

    expect(await screen.findByRole("button", { name: /#Recovered/ })).toBeDefined();
    expect(calls).toBe(2);
  });
});
