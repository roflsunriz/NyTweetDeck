import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./app";
import { type AppLayout, createDefaultLayout, layoutStorageKey } from "./model/layout";
import { exportLayoutSettings } from "./model/layout-transfer";

const originalFetch = globalThis.fetch;
const originalOpen = window.open;
let sharedSnapshot: { revision: number; layout: AppLayout } | null;
type FetchHandler = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>;

function withSharedLayoutApi(fallback: FetchHandler): typeof fetch {
  return (async (input, init) => {
    const url = String(input);
    if (url.endsWith("/api/v1/settings/layout")) {
      if (init?.method === "PUT") {
        const request = JSON.parse(String(init.body)) as {
          expectedRevision: number;
          layout: AppLayout;
        };
        const currentRevision = sharedSnapshot?.revision ?? 0;
        if (request.expectedRevision !== currentRevision) {
          return Response.json(sharedSnapshot, { status: 409 });
        }
        sharedSnapshot = { revision: currentRevision + 1, layout: request.layout };
        return Response.json(sharedSnapshot);
      }
      return sharedSnapshot === null
        ? new Response(null, { status: 204 })
        : Response.json(sharedSnapshot);
    }
    return fallback(input, init);
  }) as typeof fetch;
}

describe("NyTweetDeck shell", () => {
  beforeEach(() => {
    window.localStorage.clear();
    sharedSnapshot = null;
    globalThis.fetch = withSharedLayoutApi(async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/accounts")) {
        return Response.json([]);
      }
      return Response.json(null);
    });
  });

  afterEach(() => {
    cleanup();
    globalThis.fetch = originalFetch;
    window.open = originalOpen;
  });

  test("adds, persists, and removes a column", async () => {
    const user = userEvent.setup();
    const firstRender = render(<App />);

    expect(await screen.findByText("カラムがありません")).toBeDefined();
    const addColumnButton = screen
      .getAllByRole("button", { name: "カラムを追加" })
      .find((button) => button.classList.contains("large-add-button"));
    if (addColumnButton === undefined) {
      throw new Error("カラム追加ボタンが見つかりません。");
    }
    await user.click(addColumnButton);
    await user.click(screen.getByRole("button", { name: /おすすめ/ }));
    expect(screen.getByRole("heading", { name: "おすすめ" })).toBeDefined();

    await waitFor(() => expect(sharedSnapshot?.layout.columns).toHaveLength(1));
    firstRender.unmount();
    render(<App />);
    expect(await screen.findByRole("heading", { name: "おすすめ" })).toBeDefined();

    await user.click(screen.getByRole("button", { name: "おすすめを削除" }));
    expect(screen.getByText("カラムがありません")).toBeDefined();
  });

  test("changes language and theme from settings", async () => {
    globalThis.fetch = withSharedLayoutApi(async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/accounts")) return Response.json([]);
      if (url.endsWith("/api/v1/system/translation-health")) {
        return Response.json({
          upstreamRequests: 20,
          upstreamSuccesses: 19,
          upstreamSuccessRate: 95,
          recentSuccessRate: 95,
          deferredRequests: 1,
          rateLimitedResponses: 0,
          rateLimit: 187,
          rateLimitRemaining: 153,
        });
      }
      return Response.json(null);
    });
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "設定" }));
    expect(await screen.findByText("通信成功率 95%（成功 19 / 通信 20）")).toBeDefined();
    expect(screen.getByText("X翻訳の残り利用枠 153 / 187")).toBeDefined();
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
    expect((screen.getByTestId("setting-video-loop") as HTMLInputElement).checked).toBe(true);
    expect((screen.getByTestId("setting-video-volume") as HTMLInputElement).value).toBe("100");
    await user.click(screen.getByTestId("setting-auto-translate-posts"));
    await user.click(screen.getByTestId("setting-reduce-motion"));
    await user.click(screen.getByTestId("setting-video-loop"));
    fireEvent.change(screen.getByTestId("setting-video-volume"), { target: { value: "35" } });
    expect(document.documentElement.dataset.fontSize).toBe("large");
    expect(document.documentElement.dataset.accent).toBe("purple");
    expect(document.documentElement.dataset.density).toBe("compact");
    expect(document.documentElement.dataset.reduceMotion).toBe("true");
    await waitFor(() => expect(sharedSnapshot?.layout.display.videoVolume).toBe(35));
    expect(sharedSnapshot?.layout.version).toBe(8);
    expect(sharedSnapshot?.layout.display.accentColor).toBe("purple");
    expect(sharedSnapshot?.layout.display.reduceMotion).toBe(true);
    expect(sharedSnapshot?.layout.display.autoTranslatePosts).toBe(false);
    expect(sharedSnapshot?.layout.display.videoLoop).toBe(false);
  });

  test("imports menu columns and display settings while preserving the selected account", async () => {
    globalThis.fetch = withSharedLayoutApi(async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/accounts")) {
        return Response.json([
          { accountId: "account-1", userId: "42", username: "alice", displayName: "Alice" },
        ]);
      }
      if (url.includes("/api/v1/timelines/")) {
        return Response.json({ posts: [], nextCursor: null });
      }
      return Response.json(null);
    });
    window.localStorage.setItem(
      layoutStorageKey,
      JSON.stringify({ ...createDefaultLayout(), activeAccountId: "account-1" }),
    );
    const imported = {
      ...createDefaultLayout(),
      navItems: ["home", "trends"] as Array<"home" | "trends">,
      columns: [{ id: "imported-home", kind: "home" as const, target: null, label: null }],
      theme: "light" as const,
      display: { ...createDefaultLayout().display, videoVolume: 35 },
    };
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "設定" }));
    const file = new File([exportLayoutSettings(imported)], "settings.json", {
      type: "application/json",
    });
    fireEvent.change(screen.getByTestId("import-settings"), { target: { files: [file] } });

    expect(await screen.findByText("設定を読み込み、自動保存しました。")).toBeDefined();
    expect(screen.getByRole("heading", { name: "おすすめ" })).toBeDefined();
    expect(document.documentElement.dataset.theme).toBe("light");
    await waitFor(() => expect(sharedSnapshot?.layout.display.videoVolume).toBe(35));
    expect(sharedSnapshot?.layout.activeAccountId).toBe("account-1");
    expect(sharedSnapshot?.layout.navItems).toEqual(["home", "trends"]);
  });

  test("opens direct messages and trends from the default menu", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "ダイレクトメッセージ" }));
    await user.click(screen.getByRole("button", { name: "トレンド" }));

    expect(screen.getByRole("heading", { name: "メッセージ" })).toBeDefined();
    expect(screen.getByRole("heading", { name: "トレンド" })).toBeDefined();
  });

  test("persists each trend filter and its submitted search history across restart", async () => {
    globalThis.fetch = withSharedLayoutApi(async (input) => {
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
    });
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

    await waitFor(() => expect(sharedSnapshot?.layout.columns[0]?.target).toBe("Technology"));
    expect(sharedSnapshot?.layout.trendSearchHistory.slice(0, 3)).toEqual([
      "Technology",
      "AI",
      "Japan",
    ]);

    firstRender.unmount();
    render(<App />);
    expect(((await screen.findByTestId("trend-filter-input")) as HTMLInputElement).value).toBe(
      "Technology",
    );
  });

  test("creates a targeted search column from the default search menu", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByRole("button", { name: "検索" }));
    await user.type(screen.getByPlaceholderText("検索語句を入力"), "NyTweetDeck");
    await user.click(screen.getByRole("button", { name: "このカラムを追加" }));

    expect(screen.getByRole("heading", { name: "検索: NyTweetDeck" })).toBeDefined();
  });

  test("prefetches list candidates and updates an open picker only when the background snapshot changes", async () => {
    let listRequests = 0;
    let updated = false;
    let failed = false;
    globalThis.fetch = withSharedLayoutApi(async (input) => {
      const url = new URL(String(input), "http://localhost");
      if (url.pathname === "/api/v1/accounts") {
        return Response.json([
          { accountId: "account-1", userId: "42", username: "alice", displayName: "Alice" },
        ]);
      }
      if (url.pathname === "/api/v1/lists") {
        listRequests += 1;
        if (failed) return new Response(null, { status: 503 });
        const source = url.searchParams.get("scope");
        const lists =
          source === "mine"
            ? [
                {
                  id: updated ? "85" : "84",
                  name: updated ? "Family" : "Friends",
                  description: null,
                  ownerName: "Alice",
                  ownerUsername: "alice",
                  memberCount: 5,
                  subscriberCount: 2,
                  source: "mine",
                },
              ]
            : [];
        return Response.json({ lists, nextCursor: null });
      }
      return Response.json({ connected: true, topicCount: 0 });
    });
    window.localStorage.setItem(
      layoutStorageKey,
      JSON.stringify({ ...createDefaultLayout(), activeAccountId: "account-1" }),
    );
    const user = userEvent.setup();
    render(<App />);

    await waitFor(() => expect(listRequests).toBe(2));
    await user.click(
      screen.getAllByRole("button", { name: "カラムを追加" }).at(-1) as HTMLButtonElement,
    );
    const listKind = document.querySelector('[data-column-kind="list"]');
    if (!(listKind instanceof HTMLButtonElement)) throw new Error("リスト種別が見つかりません。");
    await user.click(listKind);

    expect(screen.getByRole("button", { name: /Friends/ })).toBeDefined();
    expect(listRequests).toBe(2);

    updated = true;
    window.dispatchEvent(new Event("focus"));

    expect(await screen.findByRole("button", { name: /Family/ })).toBeDefined();
    expect(screen.queryByRole("button", { name: /Friends/ })).toBeNull();
    expect(listRequests).toBe(4);

    const unchangedFamily = screen.getByRole("button", { name: /Family/ });
    window.dispatchEvent(new Event("focus"));
    await waitFor(() => expect(listRequests).toBe(6));
    expect(screen.getByRole("button", { name: /Family/ })).toBe(unchangedFamily);

    failed = true;
    window.dispatchEvent(new Event("focus"));
    await waitFor(() => expect(listRequests).toBe(8));
    expect(screen.getByRole("button", { name: /Family/ })).toBe(unchangedFamily);
    expect(document.querySelector(".column-target-form .setup-error")).toBeNull();
  });

  test("clears a persisted account that is no longer in the automatic account store", async () => {
    let timelineRequests = 0;
    globalThis.fetch = withSharedLayoutApi(async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/accounts")) return Response.json([]);
      if (url.includes("/api/v1/timelines/")) timelineRequests += 1;
      return Response.json(null);
    });
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
    globalThis.fetch = withSharedLayoutApi(async (input) => {
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
    });
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

  test("selects the first saved account and displays persisted columns without interaction", async () => {
    const timelineAccountIds: string[] = [];
    globalThis.fetch = withSharedLayoutApi(async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/accounts")) {
        return Response.json([
          { accountId: "account-1", userId: "42", username: "alice", displayName: "Alice" },
          { accountId: "account-2", userId: "84", username: "bob", displayName: "Bob" },
        ]);
      }
      if (url.includes("/api/v1/timelines/")) {
        timelineAccountIds.push(
          new URL(url, "http://localhost").searchParams.get("accountId") ?? "",
        );
        return Response.json({ posts: [], nextCursor: null });
      }
      return Response.json({ connected: true, topicCount: 0 });
    });
    window.localStorage.setItem(
      layoutStorageKey,
      JSON.stringify({
        ...createDefaultLayout(),
        columns: [{ id: "home", kind: "home", target: null, label: null }],
      }),
    );

    render(<App />);

    await waitFor(() => expect(timelineAccountIds).toEqual(["account-1"]));
    expect(screen.getByText("表示するポストがありません。")).toBeDefined();
    await waitFor(() => expect(sharedSnapshot?.layout.activeAccountId).toBe("account-1"));
    expect(screen.queryByRole("heading", { name: "アカウントを選択" })).toBeNull();
  });

  test("prefers the previously selected saved account over the first account", async () => {
    const timelineAccountIds: string[] = [];
    globalThis.fetch = withSharedLayoutApi(async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/accounts")) {
        return Response.json([
          { accountId: "account-1", userId: "42", username: "alice", displayName: "Alice" },
          { accountId: "account-2", userId: "84", username: "bob", displayName: "Bob" },
        ]);
      }
      if (url.includes("/api/v1/timelines/")) {
        timelineAccountIds.push(
          new URL(url, "http://localhost").searchParams.get("accountId") ?? "",
        );
        return Response.json({ posts: [], nextCursor: null });
      }
      return Response.json({ connected: true, topicCount: 0 });
    });
    window.localStorage.setItem(
      layoutStorageKey,
      JSON.stringify({
        ...createDefaultLayout(),
        activeAccountId: "account-2",
        columns: [{ id: "home", kind: "home", target: null, label: null }],
      }),
    );

    render(<App />);

    await waitFor(() => expect(timelineAccountIds).toEqual(["account-2"]));
    expect(screen.getByText("表示するポストがありません。")).toBeDefined();
  });

  test("falls back to the first saved account when the previous account is unavailable", async () => {
    const timelineAccountIds: string[] = [];
    globalThis.fetch = withSharedLayoutApi(async (input) => {
      const url = String(input);
      if (url.endsWith("/api/v1/accounts")) {
        return Response.json([
          { accountId: "account-1", userId: "42", username: "alice", displayName: "Alice" },
        ]);
      }
      if (url.includes("/api/v1/timelines/")) {
        timelineAccountIds.push(
          new URL(url, "http://localhost").searchParams.get("accountId") ?? "",
        );
        return Response.json({ posts: [], nextCursor: null });
      }
      return Response.json({ connected: true, topicCount: 0 });
    });
    window.localStorage.setItem(
      layoutStorageKey,
      JSON.stringify({
        ...createDefaultLayout(),
        activeAccountId: "deleted-account",
        columns: [{ id: "home", kind: "home", target: null, label: null }],
      }),
    );

    render(<App />);

    await waitFor(() => expect(timelineAccountIds).toEqual(["account-1"]));
    expect(screen.getByText("表示するポストがありません。")).toBeDefined();
    expect(screen.queryByRole("heading", { name: "アカウントを選択" })).toBeNull();
  });

  test("reorders navigation and columns through drag and drop and persists the order", async () => {
    const user = userEvent.setup();
    render(<App />);
    const navigationTransfer = dataTransfer();
    const compose = await screen.findByRole("button", { name: "ポストを作成" });
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

    await waitFor(() =>
      expect(sharedSnapshot?.layout.columns.map((column) => column.kind)).toEqual([
        "notifications",
        "home",
      ]),
    );
    expect(sharedSnapshot?.layout.navItems.slice(0, 2)).toEqual(["search", "compose"]);
  });

  test("activates optional following and official web menu destinations", async () => {
    let openedUrl = "";
    window.open = ((url) => {
      openedUrl = String(url);
      return null;
    }) as typeof window.open;
    const user = userEvent.setup();
    const view = render(<App />);
    await screen.findByText("カラムがありません");
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
