import type { Locale } from "../model/layout";
import type { TranslationOverride } from "./translations";
import { overrides as europeanOverrides } from "./locales-european";
import { overrides as rtlOverrides } from "./locales-rtl";
import { overrides as southAsianOverrides } from "./locales-south-asian";

export const localeOverrides: Partial<Record<Locale, TranslationOverride>> = {
  ...europeanOverrides,
  ...rtlOverrides,
  ...southAsianOverrides,
};
