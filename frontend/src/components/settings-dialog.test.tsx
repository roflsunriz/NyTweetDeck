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

function showSettings() {
  const layout = createDefaultLayout();
  return render(
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
}

test("checks on opening and prevents downloading an equal or older release", async () => {
  let complete: ((response: Response) => void) | undefined;
  let downloads = 0;
  globalThis.HTMLAnchorElement.prototype.click = () => {
    downloads++;
  };
  globalThis.fetch = (async (input) =>
    String(input).endsWith("/updates/desktop/latest")
      ? new Promise<Response>((resolve) => {
          complete = resolve;
        })
      : Response.json({ upstreamRequests: 0, successful: true })) as typeof fetch;
  showSettings();
  const button = screen.getByTestId("download-latest-desktop") as HTMLButtonElement;
  expect(button.disabled).toBe(true);
  await userEvent.click(button);
  complete?.(
    Response.json({
      tagName: "v1.4.1",
      assetName: "NyTweetDeck-v1.4.1.zip",
      downloadUrl:
        "https://github.com/roflsunriz/NyTweetDeck/releases/download/v1.4.1/NyTweetDeck-v1.4.1.zip",
      sizeBytes: 123,
      currentVersion: "1.4.1",
      updateAvailable: false,
    }),
  );
  await waitFor(() => expect(screen.getByText(translate("ja").desktopUpToDate)).toBeDefined());
  expect(button.disabled).toBe(true);
  await userEvent.click(button);
  expect(downloads).toBe(0);
});

test("allows retry after a failed check without downloading a current release", async () => {
  let checks = 0;
  globalThis.fetch = (async (input) => {
    if (!String(input).endsWith("/updates/desktop/latest"))
      return Response.json({ upstreamRequests: 0 });
    if (++checks === 1) return new Response(null, { status: 503 });
    return Response.json({
      tagName: "v1.4.1",
      assetName: "NyTweetDeck-v1.4.1.zip",
      downloadUrl:
        "https://github.com/roflsunriz/NyTweetDeck/releases/download/v1.4.1/NyTweetDeck-v1.4.1.zip",
      sizeBytes: 123,
      currentVersion: "1.5.0",
      updateAvailable: false,
    });
  }) as typeof fetch;
  showSettings();
  const button = screen.getByTestId("download-latest-desktop") as HTMLButtonElement;
  await waitFor(() => expect(button.disabled).toBe(false));
  await userEvent.click(button);
  await waitFor(() => expect(screen.getByText(translate("ja").desktopUpToDate)).toBeDefined());
  expect(checks).toBe(2);
  expect(button.disabled).toBe(true);
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
        currentVersion: "1.4.0",
        updateAvailable: true,
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

  await waitFor(() =>
    expect((screen.getByTestId("download-latest-desktop") as HTMLButtonElement).disabled).toBe(
      false,
    ),
  );
  await userEvent.click(screen.getByTestId("download-latest-desktop"));

  await waitFor(() => expect(downloads).toHaveLength(1));
  expect(downloads[0]).toBe(
    "https://github.com/roflsunriz/NyTweetDeck/releases/download/v1.4.1/NyTweetDeck-v1.4.1.zip",
  );
  await userEvent.click(screen.getByTestId("download-latest-desktop"));
  expect(downloads).toHaveLength(1);
  expect((screen.getByTestId("download-latest-desktop") as HTMLButtonElement).disabled).toBe(true);
  expect(requestedUrls).toContain("/api/v1/updates/desktop/latest");
  expect(screen.getByText("最新版のダウンロードを開始しました。")).toBeDefined();
});
