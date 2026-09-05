import { type CdpClient, waitForPageCondition } from "./cdp-client";

export async function installUpdateFixture(client: CdpClient): Promise<void> {
  await client.call("Page.addScriptToEvaluateOnNewDocument", {
    source: `(() => {
    const originalFetch = window.fetch.bind(window);
    window.__qaUpdateAvailable = false;
    window.__qaUpdateDownloads = 0;
    window.fetch = (input, init) => {
      if (String(input).endsWith('/api/v1/updates/desktop/latest')) {
        return Promise.resolve(Response.json({
          tagName: 'v1.4.2', assetName: 'NyTweetDeck-v1.4.2.zip',
          downloadUrl: 'https://github.com/roflsunriz/NyTweetDeck/releases/download/v1.4.2/NyTweetDeck-v1.4.2.zip',
          sizeBytes: 123, currentVersion: '1.4.2', updateAvailable: window.__qaUpdateAvailable
        }));
      }
      return originalFetch(input, init);
    };
  })()`,
  });
}

export async function verifyUpdateButton(client: CdpClient): Promise<void> {
  for (const width of [1440, 390]) {
    await client.call("Emulation.setDeviceMetricsOverride", {
      width,
      height: 900,
      deviceScaleFactor: 1,
      mobile: false,
    });
    for (const available of [false, true]) {
      await client.evaluate(`(() => {
        window.__qaUpdateAvailable = ${available};
        window.__qaUpdateDownloads = 0;
        HTMLAnchorElement.prototype.click = function () { window.__qaUpdateDownloads++; };
        document.querySelector('[data-action=open-settings]').click();
      })()`);
      await waitForPageCondition(
        client,
        `document.querySelector('[data-testid=download-latest-desktop]') !== null &&
         document.querySelector('[data-testid=download-latest-desktop]').disabled === ${!available} &&
         document.querySelector('[data-testid=desktop-update-settings] .setup-success') !== null ||
         (${available} && document.querySelector('[data-testid=download-latest-desktop]')?.disabled === false)`,
      );
      await client.evaluate(`(() => {
        const button = document.querySelector('[data-testid=download-latest-desktop]');
        button.click(); button.click();
      })()`);
      await waitForPageCondition(
        client,
        `document.querySelector('[data-testid=download-latest-desktop]').disabled === true`,
      );
      const downloads = await client.evaluate<number>("window.__qaUpdateDownloads");
      if (downloads !== (available ? 1 : 0))
        throw new Error(`Unexpected update downloads: ${downloads}`);
      await client.evaluate(
        'document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }))',
      );
      await waitForPageCondition(client, 'document.querySelector("[role=dialog]") === null');
    }
  }
}
