import { describe, expect, test } from "bun:test";
import { supportedLocales } from "../model/layout";
import { translate } from "./translations";

describe("translation coverage", () => {
  test("provides a real core dictionary for every supported locale", () => {
    const japanese = translate("ja");
    const english = translate("en");

    for (const locale of supportedLocales) {
      expect(japanese.localeName[locale].length).toBeGreaterThan(0);
      expect(translate(locale).appName).toBe("NyTweetDeck");
      expect(translate(locale).settings.length).toBeGreaterThan(0);
      expect(translate(locale).nav.home.length).toBeGreaterThan(0);
      if (locale !== "en") {
        expect(translate(locale).settings).not.toBe(english.settings);
        expect(translate(locale).column.home.title).not.toBe(english.column.home.title);
        expect(translate(locale).followUser).not.toBe(english.followUser);
        expect(translate(locale).manageLists).not.toBe(english.manageLists);
        expect(translate(locale).newPosts).not.toBe(english.newPosts);
        expect(translate(locale).replySort).not.toBe(english.replySort);
        expect(translate(locale).possibleSpamReplies(2)).not.toBe(english.possibleSpamReplies(2));
        expect(translate(locale).trendFilterLabel).not.toBe(english.trendFilterLabel);
        expect(translate(locale).communityNoteDetails).not.toBe(english.communityNoteDetails);
        expect(translate(locale).videoLoop).not.toBe(english.videoLoop);
        expect(translate(locale).videoVolume).not.toBe(english.videoVolume);
        expect(translate(locale).videoPlayer).not.toBe(english.videoPlayer);
        expect(translate(locale).playVideo).not.toBe(english.playVideo);
        expect(translate(locale).enterFullscreen).not.toBe(english.enterFullscreen);
        expect(translate(locale).pictureInPicture).not.toBe(english.pictureInPicture);
        expect(translate(locale).sharedSettingsLoadError).not.toBe(english.sharedSettingsLoadError);
        expect(translate(locale).sharedSettingsSaveError).not.toBe(english.sharedSettingsSaveError);
        expect(translate(locale).sharedSettingsConflict).not.toBe(english.sharedSettingsConflict);
      }
    }
  });
});
