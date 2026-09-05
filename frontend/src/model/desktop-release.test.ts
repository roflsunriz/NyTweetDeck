import { expect, test } from "bun:test";
import { parseDesktopRelease } from "./desktop-release";

test("accepts only the exact desktop release asset from this GitHub repository", () => {
  expect(
    parseDesktopRelease({
      tagName: "v1.4.1",
      assetName: "NyTweetDeck-v1.4.1.zip",
      downloadUrl:
        "https://github.com/roflsunriz/NyTweetDeck/releases/download/v1.4.1/NyTweetDeck-v1.4.1.zip",
      sizeBytes: 123456,
      currentVersion: "1.4.0",
      updateAvailable: true,
    }),
  ).toEqual({
    tagName: "v1.4.1",
    assetName: "NyTweetDeck-v1.4.1.zip",
    downloadUrl:
      "https://github.com/roflsunriz/NyTweetDeck/releases/download/v1.4.1/NyTweetDeck-v1.4.1.zip",
    sizeBytes: 123456,
    currentVersion: "1.4.0",
    updateAvailable: true,
  });

  for (const invalid of [
    {
      tagName: "android-v0.2.2",
      assetName: "NyTweetDeck-Android-v0.2.2.apk",
      downloadUrl:
        "https://github.com/roflsunriz/NyTweetDeck/releases/download/android-v0.2.2/NyTweetDeck-Android-v0.2.2.apk",
      sizeBytes: 123,
    },
    {
      tagName: "v1.4.1",
      assetName: "NyTweetDeck-v1.4.1.zip",
      downloadUrl:
        "https://github.com/other/repository/releases/download/v1.4.1/NyTweetDeck-v1.4.1.zip",
      sizeBytes: 123,
    },
  ]) {
    expect(() => parseDesktopRelease(invalid)).toThrow();
  }
});
