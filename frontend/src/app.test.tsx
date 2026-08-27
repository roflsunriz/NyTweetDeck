import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./app";
import { createDefaultLayout, layoutStorageKey } from "./model/layout";

const originalFetch = globalThis.fetch;
const originalOpen = window.open;

describe("NyTweetDeck shell", () => {
  beforeEach(() => {
    window.localStorage.clear();
    globalThis.fetch = (async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/accounts")) {
        return Response.json([]);
      }
      return Response.json(null);
    }) as typeof fetch;
  });

  afterEach(() => {
    cleanup();
    globalThis.fetch = originalFetch;
    window.open = originalOpen;
  });

  test("adds, persists, and removes a column", async () => {
    const user = userEvent.setup();
    const firstRender = render(<App />);

    expect(screen.getByText("カラムがありません")).toBeDefined();
    const addColumnButton = screen
      .getAllByRole("button", { name: "カラムを追加" })
      .find((button) => button.classList.contains("large-add-button"));
    if (addColumnButton === undefined) {
      throw new Error("カラム追加ボタンが見つかりません。");
    }
    await user.click(addColumnButton);
    await user.click(screen.getByRole("button", { name: /おすすめ/ }));
    expect(screen.getByRole("heading", { name: "おすすめ" })).toBeDefined();

    firstRender.unmount();
    render(<App />);
    expect(screen.getByRole("heading", { name: "おすすめ" })).toBeDefined();

    await user.click(screen.getByRole("button", { name: "おすすめを削除" }));
    expect(screen.getByText("カラムがありません")).toBeDefined();
  });

  test("changes language and theme from settings", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "設定" }));
    await user.selectOptions(screen.getByTestId("setting-language"), "en");

    expect(screen.getByRole("heading", { name: "Settings" })).toBeDefined();
    await user.selectOptions(screen.getByTestId("setting-theme"), "light");
    expect(document.documentElement.dataset.theme).toBe("light");
    expect(document.documentElement.lang).toBe("en");
    await user.selectOptions(screen.getByTestId("setting-language"), "ar");
    expect(document.documentElement.lang).toBe("ar");
    expect(document.documentElement.dir).toBe("rtl");
    await user.selectOptions(screen.getByTestId("setting-font-size"), "large");
    await user.selectOptions(screen.getByTestId("setting-accent-color"), "purple");
    await user.selectOptions(screen.getByTestId("setting-density"), "compact");
    expect((screen.getByTestId("setting-auto-translate-posts") as HTMLInputElement).checked).toBe(
      true,
    );
    await user.click(screen.getByTestId("setting-auto-translate-posts"));
    await user.click(screen.getByTestId("setting-reduce-motion"));
    expect(document.documentElement.dataset.fontSize).toBe("large");
    expect(document.documentElement.dataset.accent).toBe("purple");
    expect(document.documentElement.dataset.density).toBe("compact");
    expect(document.documentElement.dataset.reduceMotion).toBe("true");
    const stored = JSON.parse(String(window.localStorage.getItem("nytweetdeck.layout"))) as {
      version: number;
      display: { accentColor: string; reduceMotion: boolean };
    };
    expect(stored.version).toBe(6);
    expect(stored.display.accentColor).toBe("purple");
    expect(stored.display.reduceMotion).toBe(true);
    expect((stored.display as { autoTranslatePosts?: boolean }).autoTranslatePosts).toBe(false);
  });

  test("opens direct messages and trends from the default menu", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "ダイレクトメッセージ" }));
    await user.click(screen.getByRole("button", { name: "トレンド" }));

    expect(screen.getByRole("heading", { name: "メッセージ" })).toBeDefined();
    expect(screen.getByRole("heading", { name: "トレンド" })).toBeDefined();
  });

  test("persists each trend filter and its submitted search history across restart", async () => {
    globalThis.fetch = (async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/accounts")) {
        return Response.json([
          { accountId: "account-1", userId: "42", username: "alice", displayName: "Alice" },
        ]);
      }
      if (url.includes("/api/v1/trends")) {
        return Response.json({
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
        });
      }
      return Response.json(null);
    }) as typeof fetch;
    window.localStorage.setItem(
      layoutStorageKey,
      JSON.stringify({
        ...createDefaultLayout(),
        activeAccountId: "account-1",
        columns: [{ id: "trends", kind: "trends", target: "AI", label: null }],
        trendSearchHistory: ["AI", "Japan"],
      }),
    );
    const user = userEvent.setup();
    const firstRender = render(<App />);

    const input = (await screen.findByTestId("trend-filter-input")) as HTMLInputElement;
    expect(input.value).toBe("AI");
    expect(firstRender.container.querySelector('option[value="Japan"]')).not.toBeNull();
    await user.clear(input);
    await user.type(input, "Technology{Enter}");
    expect(await screen.findByText("#NyTweetDeck")).toBeDefined();

    const stored = JSON.parse(String(window.localStorage.getItem(layoutStorageKey))) as {
      columns: Array<{ target: string | null }>;
      trendSearchHistory: string[];
    };
    expect(stored.columns[0]?.target).toBe("Technology");
    expect(stored.trendSearchHistory.slice(0, 3)).toEqual(["Technology", "AI", "Japan"]);

    firstRender.unmount();
    render(<App />);
    expect(((await screen.findByTestId("trend-filter-input")) as HTMLInputElement).value).toBe(
      "Technology",
    );
  });

  test("creates a targeted search column from the default search menu", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "検索" }));
    await user.type(screen.getByPlaceholderText("検索語句を入力"), "NyTweetDeck");
    await user.click(screen.getByRole("button", { name: "このカラムを追加" }));

    expect(screen.getByRole("heading", { name: "検索: NyTweetDeck" })).toBeDefined();
  });

  test("clears a persisted account that is no longer in the automatic account store", async () => {
    let timelineRequests = 0;
    globalThis.fetch = (async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/accounts")) return Response.json([]);
      if (url.includes("/api/v1/timelines/")) timelineRequests += 1;
      return Response.json(null);
    }) as typeof fetch;
    window.localStorage.setItem(
      layoutStorageKey,
      JSON.stringify({
        ...createDefaultLayout(),
        activeAccountId: "account-1",
        columns: [{ id: "search", kind: "search", target: "NyTweetDeck", label: "NyTweetDeck" }],
      }),
    );

    render(<App />);

    expect(await screen.findByRole("heading", { name: "アカウントを選択" })).toBeDefined();
    expect(screen.getByText("ログインが必要です")).toBeDefined();
    expect(timelineRequests).toBe(0);
  });

  test("loads a persisted account and its columns automatically after restart", async () => {
    let timelineRequests = 0;
    globalThis.fetch = (async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/accounts")) {
        return Response.json([
          { accountId: "account-1", userId: "42", username: "alice", displayName: "Alice" },
        ]);
      }
      if (url.includes("/api/v1/timelines/")) {
        timelineRequests += 1;
        return Response.json({ posts: [], nextCursor: null });
      }
      return Response.json({ connected: true, topicCount: 0 });
    }) as typeof fetch;
    window.localStorage.setItem(
      layoutStorageKey,
      JSON.stringify({
        ...createDefaultLayout(),
        activeAccountId: "account-1",
        columns: [{ id: "home", kind: "home", target: null, label: null }],
      }),
    );

    render(<App />);

    expect(await screen.findByText("表示するポストがありません。")).toBeDefined();
    expect(timelineRequests).toBe(1);
  });

  test("reorders navigation and columns through drag and drop and persists the order", async () => {
    const user = userEvent.setup();
    render(<App />);
    const navigationTransfer = dataTransfer();
    const compose = screen.getByRole("button", { name: "ポストを作成" });
    const search = screen.getByRole("button", { name: "検索" });

    fireEvent.dragStart(compose, { dataTransfer: navigationTransfer });
    fireEvent.drop(search, { dataTransfer: navigationTransfer });

    await user.click(screen.getByRole("button", { name: "ホーム" }));
    await user.click(screen.getByRole("button", { name: "通知" }));
    const homeColumn = screen.getByRole("heading", { name: "おすすめ" }).closest("article");
    const notificationColumn = screen.getByRole("heading", { name: "通知" }).closest("article");
    if (homeColumn === null || notificationColumn === null) {
      throw new Error("並べ替え対象のカラムが見つかりません。");
    }
    const columnTransfer = dataTransfer();
    fireEvent.dragStart(homeColumn, { dataTransfer: columnTransfer });
    fireEvent.drop(notificationColumn, { dataTransfer: columnTransfer });

    const stored = JSON.parse(String(window.localStorage.getItem("nytweetdeck.layout"))) as {
      navItems: string[];
      columns: Array<{ kind: string }>;
    };
    expect(stored.navItems.slice(0, 2)).toEqual(["search", "compose"]);
    expect(stored.columns.map((column) => column.kind)).toEqual(["notifications", "home"]);
  });

  test("activates optional following and official web menu destinations", async () => {
    let openedUrl = "";
    window.open = ((url) => {
      openedUrl = String(url);
      return null;
    }) as typeof window.open;
    const user = userEvent.setup();
    const view = render(<App />);
    const editMenu = view.container.querySelector('[data-action="edit-menu"]');
    if (!(editMenu instanceof HTMLButtonElement)) {
      throw new Error("メニュー編集ボタンが見つかりません。");
    }

    await user.click(editMenu);
    await user.click(screen.getByRole("button", { name: "フォローする" }));
    await user.click(screen.getByRole("button", { name: "Grok" }));
    await user.click(screen.getByRole("button", { name: "閉じる" }));
    await user.click(screen.getByRole("button", { name: "フォローする" }));
    expect(screen.getByRole("heading", { name: "フォロー中" })).toBeDefined();

    await user.click(screen.getByRole("button", { name: "Grok" }));
    expect(openedUrl).toBe("https://x.com/i/grok");
  });
});

function dataTransfer(): DataTransfer {
  const entries = new Map<string, string>();
  return {
    setData: (type: string, value: string) => entries.set(type, value),
    getData: (type: string) => entries.get(type) ?? "",
  } as unknown as DataTransfer;
}
