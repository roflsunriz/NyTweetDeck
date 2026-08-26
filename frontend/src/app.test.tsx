import { afterEach, beforeEach, describe, expect, test } from "bun:test";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./app";

const originalFetch = globalThis.fetch;

describe("NyTweetDeck shell", () => {
  beforeEach(() => {
    window.localStorage.clear();
    globalThis.fetch = (async (input) => {
      const url = String(input);
      if (url.endsWith("/readiness")) {
        return Response.json({
          androidApiVersion: "12.19.1-release.0",
          clientCredentialsAvailable: false,
          deviceProfileAvailable: false,
        });
      }
      if (url.endsWith("/vault/status")) {
        return Response.json({ exists: false, unlocked: false, accountCount: 0, unlockedAt: null });
      }
      if (url.endsWith("/vault/accounts")) {
        return Response.json([]);
      }
      return Response.json(null);
    }) as typeof fetch;
  });

  afterEach(() => {
    cleanup();
    globalThis.fetch = originalFetch;
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
    await user.click(screen.getByTestId("setting-reduce-motion"));
    expect(document.documentElement.dataset.fontSize).toBe("large");
    expect(document.documentElement.dataset.accent).toBe("purple");
    expect(document.documentElement.dataset.density).toBe("compact");
    expect(document.documentElement.dataset.reduceMotion).toBe("true");
    const stored = JSON.parse(String(window.localStorage.getItem("nytweetdeck.layout"))) as {
      version: number;
      display: { accentColor: string; reduceMotion: boolean };
    };
    expect(stored.version).toBe(3);
    expect(stored.display.accentColor).toBe("purple");
    expect(stored.display.reduceMotion).toBe(true);
  });

  test("opens direct messages and trends from the default menu", async () => {
    const user = userEvent.setup();
    render(<App />);

    await user.click(screen.getByRole("button", { name: "ダイレクトメッセージ" }));
    await user.click(screen.getByRole("button", { name: "トレンド" }));

    expect(screen.getByRole("heading", { name: "メッセージ" })).toBeDefined();
    expect(screen.getByRole("heading", { name: "トレンド" })).toBeDefined();
  });
});
