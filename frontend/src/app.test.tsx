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
    const addColumnButton = screen.getAllByRole("button", { name: "カラムを追加" }).at(0);
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
    await user.selectOptions(screen.getByLabelText("表示言語"), "en");

    expect(screen.getByRole("heading", { name: "Settings" })).toBeDefined();
    await user.selectOptions(screen.getByLabelText("Theme"), "light");
    expect(document.documentElement.dataset.theme).toBe("light");
    expect(document.documentElement.lang).toBe("en");
  });
});
