import type { Translation } from "../i18n/translations";
import type {
  AccentColor,
  Density,
  DisplayPreferences,
  FontSize,
  Locale,
  Theme,
} from "../model/layout";
import { supportedLocales } from "../model/layout";
import { Modal } from "./modal";
import { AccountVaultSetup } from "./account-vault-setup";
import { XApiSetup } from "./x-api-setup";

interface SettingsDialogProps {
  translation: Translation;
  locale: Locale;
  theme: Theme;
  display: DisplayPreferences;
  onLocaleChange: (locale: Locale) => void;
  onThemeChange: (theme: Theme) => void;
  onDisplayChange: (display: DisplayPreferences) => void;
  onClose: () => void;
}

export function SettingsDialog({
  translation,
  locale,
  theme,
  display,
  onLocaleChange,
  onThemeChange,
  onDisplayChange,
  onClose,
}: SettingsDialogProps) {
  return (
    <Modal title={translation.settings} closeLabel={translation.close} onClose={onClose}>
      <div className="settings-form">
        <label>
          <span>{translation.language}</span>
          <select
            data-testid="setting-language"
            value={locale}
            onChange={(event) => onLocaleChange(event.target.value as Locale)}
          >
            {supportedLocales.map((supportedLocale) => (
              <option key={supportedLocale} value={supportedLocale}>
                {translation.localeName[supportedLocale]}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>{translation.theme}</span>
          <select
            data-testid="setting-theme"
            value={theme}
            onChange={(event) => onThemeChange(event.target.value as Theme)}
          >
            <option value="system">{translation.themeName.system}</option>
            <option value="light">{translation.themeName.light}</option>
            <option value="dark">{translation.themeName.dark}</option>
          </select>
        </label>
        <h3>{translation.displayAndAccessibility}</h3>
        <label>
          <span>{translation.fontSize}</span>
          <select
            data-testid="setting-font-size"
            value={display.fontSize}
            onChange={(event) =>
              onDisplayChange({ ...display, fontSize: event.target.value as FontSize })
            }
          >
            {(["small", "default", "large"] as const).map((value) => (
              <option key={value} value={value}>
                {translation.fontSizeName[value]}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>{translation.accentColor}</span>
          <select
            data-testid="setting-accent-color"
            value={display.accentColor}
            onChange={(event) =>
              onDisplayChange({ ...display, accentColor: event.target.value as AccentColor })
            }
          >
            {(["blue", "yellow", "pink", "purple", "orange", "green"] as const).map((value) => (
              <option key={value} value={value}>
                {translation.accentColorName[value]}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>{translation.density}</span>
          <select
            data-testid="setting-density"
            value={display.density}
            onChange={(event) =>
              onDisplayChange({ ...display, density: event.target.value as Density })
            }
          >
            {(["comfortable", "compact"] as const).map((value) => (
              <option key={value} value={value}>
                {translation.densityName[value]}
              </option>
            ))}
          </select>
        </label>
        <ToggleSetting
          id="reduce-motion"
          label={translation.reduceMotion}
          checked={display.reduceMotion}
          onChange={(checked) => onDisplayChange({ ...display, reduceMotion: checked })}
        />
        <ToggleSetting
          id="media-preview"
          label={translation.mediaPreview}
          checked={display.mediaPreview}
          onChange={(checked) => onDisplayChange({ ...display, mediaPreview: checked })}
        />
        <ToggleSetting
          id="video-autoplay"
          label={translation.videoAutoplay}
          checked={display.videoAutoplay}
          onChange={(checked) => onDisplayChange({ ...display, videoAutoplay: checked })}
        />
      </div>
      <XApiSetup translation={translation} />
      <AccountVaultSetup translation={translation} />
    </Modal>
  );
}

function ToggleSetting({
  id,
  label,
  checked,
  onChange,
}: {
  id: string;
  label: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className="toggle-setting">
      <span>{label}</span>
      <input
        data-testid={`setting-${id}`}
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
      />
    </label>
  );
}
