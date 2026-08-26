import type { Translation } from "../i18n/translations";
import type { Locale, Theme } from "../model/layout";
import { Modal } from "./modal";
import { XApiSetup } from "./x-api-setup";

interface SettingsDialogProps {
  translation: Translation;
  locale: Locale;
  theme: Theme;
  onLocaleChange: (locale: Locale) => void;
  onThemeChange: (theme: Theme) => void;
  onClose: () => void;
}

export function SettingsDialog({
  translation,
  locale,
  theme,
  onLocaleChange,
  onThemeChange,
  onClose,
}: SettingsDialogProps) {
  return (
    <Modal title={translation.settings} closeLabel={translation.close} onClose={onClose}>
      <div className="settings-form">
        <label>
          <span>{translation.language}</span>
          <select value={locale} onChange={(event) => onLocaleChange(event.target.value as Locale)}>
            <option value="ja">{translation.localeName.ja}</option>
            <option value="en">{translation.localeName.en}</option>
          </select>
        </label>
        <label>
          <span>{translation.theme}</span>
          <select value={theme} onChange={(event) => onThemeChange(event.target.value as Theme)}>
            <option value="system">{translation.themeName.system}</option>
            <option value="light">{translation.themeName.light}</option>
            <option value="dark">{translation.themeName.dark}</option>
          </select>
        </label>
      </div>
      <XApiSetup translation={translation} />
    </Modal>
  );
}
