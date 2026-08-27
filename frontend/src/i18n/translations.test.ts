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
        expect(translate(locale).trendFilterLabel).not.toBe(english.trendFilterLabel);
        expect(translate(locale).communityNoteDetails).not.toBe(english.communityNoteDetails);
        expect(translate(locale).videoLoop).not.toBe(english.videoLoop);
        expect(translate(locale).videoVolume).not.toBe(english.videoVolume);
      }
    }
  });
});
