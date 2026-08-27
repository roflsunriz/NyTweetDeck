import { describe, expect, test } from "bun:test";
import { type AppLayout, createDefaultLayout } from "./layout";
import {
  exportLayoutSettings,
  importLayoutSettings,
  layoutTransferFormat,
  layoutTransferVersion,
} from "./layout-transfer";

describe("layout settings transfer", () => {
  test("round-trips menus columns and display settings without exporting an account id", () => {
    const source: AppLayout = {
      ...createDefaultLayout(),
      activeAccountId: "account-secret-id",
      navItems: ["home", "search"],
      columns: [
        { id: "home", kind: "home" as const, target: null, label: null },
        { id: "search", kind: "search" as const, target: "NyTweetDeck", label: "NyTweetDeck" },
      ],
      display: { ...createDefaultLayout().display, videoVolume: 35 },
      trendSearchHistory: ["AI"],
    };

    const serialized = exportLayoutSettings(source, new Date("2026-08-27T00:00:00Z"));
    const document = JSON.parse(serialized) as Record<string, unknown>;
    const imported = importLayoutSettings(serialized, {
      ...createDefaultLayout(),
      activeAccountId: "current-account",
    });

    expect(document.format).toBe(layoutTransferFormat);
    expect(document.version).toBe(layoutTransferVersion);
    expect(serialized).not.toContain("account-secret-id");
    expect(imported.activeAccountId).toBe("current-account");
    expect(imported.navItems).toEqual(["home", "search"]);
    expect(imported.columns).toEqual(source.columns);
    expect(imported.display.videoVolume).toBe(35);
    expect(imported.trendSearchHistory).toEqual(["AI"]);
  });

  test("rejects malformed unsupported and schema-invalid documents", () => {
    const current = createDefaultLayout();

    expect(() => importLayoutSettings("not json", current)).toThrow("有効なJSON");
    expect(() =>
      importLayoutSettings(JSON.stringify({ format: "other", version: 1 }), current),
    ).toThrow("NyTweetDeck設定ファイル");
    expect(() =>
      importLayoutSettings(
        JSON.stringify({
          format: layoutTransferFormat,
          version: 999,
          exportedAt: new Date().toISOString(),
          layout: current,
        }),
        current,
      ),
    ).toThrow("バージョン");
    expect(() =>
      importLayoutSettings(
        JSON.stringify({
          format: layoutTransferFormat,
          version: layoutTransferVersion,
          exportedAt: new Date().toISOString(),
          layout: { ...current, columns: [{ id: "bad", kind: "unknown" }] },
        }),
        current,
      ),
    ).toThrow("レイアウト");
  });
});
