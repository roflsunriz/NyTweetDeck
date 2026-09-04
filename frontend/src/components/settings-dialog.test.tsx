import { afterEach, expect, test } from "bun:test";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { translate } from "../i18n/translations";
import { createDefaultLayout } from "../model/layout";
import { SettingsDialog } from "./settings-dialog";

const originalFetch = globalThis.fetch;
const originalAnchorClick = globalThis.HTMLAnchorElement.prototype.click;

afterEach(() => {
  cleanup();
  globalThis.fetch = originalFetch;
  globalThis.HTMLAnchorElement.prototype.click = originalAnchorClick;
});

test("downloads the latest validated desktop release from settings", async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = (async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url === "/api/v1/updates/desktop/latest") {
      return Response.json({
        tagName: "v1.4.1",
        assetName: "NyTweetDeck-v1.4.1.zip",
        downloadUrl:
          "https://github.com/roflsunriz/NyTweetDeck/releases/download/v1.4.1/NyTweetDeck-v1.4.1.zip",
        sizeBytes: 123456,
      });
    }
    if (url === "/api/v1/system/translation-health") {
      return Response.json({ upstreamRequests: 0 });
    }
    return Response.json({ refreshing: false, successful: true, errorCode: null });
  }) as typeof fetch;
  const downloads: string[] = [];
  globalThis.HTMLAnchorElement.prototype.click = function click() {
    downloads.push(this.href);
  };
  const layout = createDefaultLayout();
  render(
    <SettingsDialog
      translation={translate("ja")}
      locale={layout.locale}
      translationLocale={layout.translationLocale}
      theme={layout.theme}
      display={layout.display}
      layout={layout}
      onLocaleChange={() => undefined}
      onTranslationLocaleChange={() => undefined}
      onThemeChange={() => undefined}
      onDisplayChange={() => undefined}
      onLayoutImport={() => undefined}
      onClose={() => undefined}
    />,
  );

  await userEvent.click(screen.getByTestId("download-latest-desktop"));

  await waitFor(() => expect(downloads).toHaveLength(1));
  expect(downloads[0]).toBe(
    "https://github.com/roflsunriz/NyTweetDeck/releases/download/v1.4.1/NyTweetDeck-v1.4.1.zip",
  );
  expect(requestedUrls).toContain("/api/v1/updates/desktop/latest");
  expect(screen.getByText("最新版のダウンロードを開始しました。")).toBeDefined();
});
