import { describe, expect, test } from "bun:test";
import { supportedLocales } from "../model/layout";
import { translate } from "./translations";

describe("translation coverage", () => {
  test("provides every supported locale name and a safe dictionary fallback", () => {
    const japanese = translate("ja");

    for (const locale of supportedLocales) {
      expect(japanese.localeName[locale].length).toBeGreaterThan(0);
      expect(translate(locale).appName).toBe("NyTweetDeck");
    }
  });
});
