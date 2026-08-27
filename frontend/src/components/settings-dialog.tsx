import { useEffect, useState } from "react";
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
          id="auto-translate-posts"
          label={translation.autoTranslatePosts}
          checked={display.autoTranslatePosts}
          onChange={(checked) => onDisplayChange({ ...display, autoTranslatePosts: checked })}
        />
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
        <ToggleSetting
          id="video-loop"
          label={translation.videoLoop}
          checked={display.videoLoop}
          onChange={(checked) => onDisplayChange({ ...display, videoLoop: checked })}
        />
        <label className="video-volume-setting">
          <span>
            {translation.videoVolume}: <output>{display.videoVolume}%</output>
          </span>
          <input
            data-testid="setting-video-volume"
            type="range"
            min="0"
            max="100"
            step="5"
            value={display.videoVolume}
            onChange={(event) =>
              onDisplayChange({ ...display, videoVolume: Number(event.target.value) })
            }
          />
        </label>
      </div>
      <ApiMetadataSettings translation={translation} />
    </Modal>
  );
}

interface MetadataStatus {
  refreshing: boolean;
  successful: boolean;
  lastSuccessfulAt: string | null;
  sourceVersion: string | null;
  updatedOperations: number;
  errorCode: string | null;
}

function ApiMetadataSettings({ translation }: { translation: Translation }) {
  const [status, setStatus] = useState<MetadataStatus | null>(null);
  const [requestFailed, setRequestFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/v1/x-api/refresh/status", { signal: controller.signal })
      .then(readMetadataStatus)
      .then(setStatus)
      .catch((error) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) {
          setRequestFailed(true);
        }
      });
    return () => controller.abort();
  }, []);

  const refresh = async () => {
    setRequestFailed(false);
    try {
      const response = await fetch("/api/v1/x-api/refresh", { method: "POST" });
      setStatus(await readMetadataStatus(response));
    } catch {
      setRequestFailed(true);
    }
  };

  return (
    <section className="metadata-settings">
      <h3>{translation.apiMetadata}</h3>
      <p>{translation.apiMetadataDescription}</p>
      <p className={status?.successful ? "setup-success" : "inline-warning"}>
        {requestFailed || (status !== null && status.errorCode !== null)
          ? translation.apiMetadataFailed
          : status?.successful
            ? translation.apiMetadataCurrent
            : translation.apiMetadataFallback}
      </p>
      {status?.sourceVersion !== null && status?.sourceVersion !== undefined && (
        <small>{status.sourceVersion}</small>
      )}
      <button
        className="secondary-button"
        data-testid="refresh-api-metadata"
        type="button"
        disabled={status?.refreshing === true}
        onClick={refresh}
      >
        {status?.refreshing === true ? translation.loading : translation.apiMetadataUpdate}
      </button>
    </section>
  );
}

async function readMetadataStatus(response: Response): Promise<MetadataStatus> {
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return (await response.json()) as MetadataStatus;
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
