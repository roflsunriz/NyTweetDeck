import { useEffect, useRef, useState } from "react";
import type { Translation } from "../i18n/translations";
import type {
  AccentColor,
  AppLayout,
  Density,
  DisplayPreferences,
  FontSize,
  Locale,
  Theme,
} from "../model/layout";
import { supportedLocales } from "../model/layout";
import {
  type DesktopRelease,
  downloadDesktopRelease,
  loadLatestDesktopRelease,
} from "../model/desktop-release";
import { exportLayoutSettings, importLayoutSettings } from "../model/layout-transfer";
import { Modal } from "./modal";

interface SettingsDialogProps {
  translation: Translation;
  locale: Locale;
  translationLocale: Locale;
  theme: Theme;
  display: DisplayPreferences;
  layout: AppLayout;
  onLocaleChange: (locale: Locale) => void;
  onTranslationLocaleChange: (locale: Locale) => void;
  onThemeChange: (theme: Theme) => void;
  onDisplayChange: (display: DisplayPreferences) => void;
  onLayoutImport: (layout: AppLayout) => void;
  onClose: () => void;
}

export function SettingsDialog({
  translation,
  locale,
  translationLocale,
  theme,
  display,
  layout,
  onLocaleChange,
  onTranslationLocaleChange,
  onThemeChange,
  onDisplayChange,
  onLayoutImport,
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
          <span>{translation.translationLanguage}</span>
          <select
            data-testid="setting-translation-language"
            value={translationLocale}
            onChange={(event) => onTranslationLocaleChange(event.target.value as Locale)}
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
          id="auto-refresh-timelines"
          label={translation.autoRefreshTimelines}
          checked={display.autoRefreshTimelines}
          onChange={(checked) => onDisplayChange({ ...display, autoRefreshTimelines: checked })}
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
        <label>
          <span>{translation.videoQuality}</span>
          <select
            data-testid="setting-video-quality"
            value={display.videoQuality}
            onChange={(event) =>
              onDisplayChange({
                ...display,
                videoQuality: event.target.value as DisplayPreferences["videoQuality"],
              })
            }
          >
            <option value="auto">{translation.videoQualityAuto}</option>
            <option value="low">{translation.videoQualityLow}</option>
            <option value="medium">{translation.videoQualityMedium}</option>
            <option value="high">{translation.videoQualityHigh}</option>
          </select>
        </label>
        <label>
          <span>{translation.navigationPosition}</span>
          <select
            data-testid="setting-navigation-position"
            value={display.navigationPosition}
            onChange={(event) =>
              onDisplayChange({
                ...display,
                navigationPosition: event.target.value as DisplayPreferences["navigationPosition"],
              })
            }
          >
            <option value="left">{translation.navigationPositionLeft}</option>
            <option value="bottom">{translation.navigationPositionBottom}</option>
          </select>
        </label>
        <ToggleSetting
          id="show-main-navigation"
          label={translation.showMainNavigation}
          checked={display.showMainNavigation}
          onChange={(checked) => onDisplayChange({ ...display, showMainNavigation: checked })}
        />
        <p className="inline-warning" style={{ gridColumn: "1 / -1", marginTop: "-8px" }}>
          {translation.showMainNavigationDescription}
        </p>
      </div>
      <LayoutTransferSettings layout={layout} translation={translation} onImport={onLayoutImport} />
      <DesktopUpdateSettings translation={translation} />
      <TranslationHealthSettings translation={translation} />
      <ApiMetadataSettings translation={translation} />
    </Modal>
  );
}

const maximumSettingsFileBytes = 256 * 1_024;

function DesktopUpdateSettings({ translation }: { translation: Translation }) {
  const [status, setStatus] = useState<"checking" | "ready" | "current" | "started" | "failed">(
    "checking",
  );
  const [release, setRelease] = useState<DesktopRelease | null>(null);
  const busy = useRef(false);

  useEffect(() => {
    let active = true;
    void loadLatestDesktopRelease()
      .then((latest) => {
        if (!active) return;
        setRelease(latest);
        setStatus(latest.updateAvailable ? "ready" : "current");
      })
      .catch(() => {
        if (active) setStatus("failed");
      });
    return () => {
      active = false;
    };
  }, []);

  const downloadLatest = async () => {
    if (busy.current || (status !== "ready" && status !== "failed")) return;
    busy.current = true;
    setStatus("checking");
    try {
      const latest = release ?? (await loadLatestDesktopRelease());
      setRelease(latest);
      if (!latest.updateAvailable) {
        setStatus("current");
        return;
      }
      downloadDesktopRelease(latest);
      setStatus("started");
    } catch {
      setStatus("failed");
      busy.current = false;
    }
  };

  return (
    <section className="metadata-settings" data-testid="desktop-update-settings">
      <h3>{translation.appUpdate}</h3>
      <p>{translation.appUpdateDescription}</p>
      <button
        className="secondary-button"
        data-testid="download-latest-desktop"
        type="button"
        disabled={status !== "ready" && status !== "failed"}
        onClick={() => void downloadLatest()}
      >
        {status === "checking"
          ? translation.checkingLatestDesktop
          : translation.downloadLatestDesktop}
      </button>
      {status === "current" && <p className="setup-success">{translation.desktopUpToDate}</p>}
      {status === "started" && (
        <p className="setup-success">{translation.desktopDownloadStarted}</p>
      )}
      {status === "failed" && <p className="inline-warning">{translation.desktopDownloadFailed}</p>}
    </section>
  );
}

function LayoutTransferSettings({
  layout,
  translation,
  onImport,
}: {
  layout: AppLayout;
  translation: Translation;
  onImport: (layout: AppLayout) => void;
}) {
  const [status, setStatus] = useState<"imported" | "failed" | null>(null);

  const exportSettings = () => {
    const blob = new Blob([exportLayoutSettings(layout)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `NyTweetDeck-settings-${new Date().toISOString().slice(0, 10)}.json`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const importSettings = async (file: File | undefined) => {
    if (file === undefined) return;
    setStatus(null);
    try {
      if (file.size > maximumSettingsFileBytes) throw new Error("settings file too large");
      onImport(importLayoutSettings(await file.text(), layout));
      setStatus("imported");
    } catch {
      setStatus("failed");
    }
  };

  return (
    <section className="metadata-settings" data-testid="layout-transfer-settings">
      <h3>{translation.settingsTransfer}</h3>
      <p>{translation.settingsTransferDescription}</p>
      <div className="settings-transfer-actions">
        <button
          className="secondary-button"
          data-testid="export-settings"
          type="button"
          onClick={exportSettings}
        >
          {translation.exportSettings}
        </button>
        <label className="secondary-button settings-import-button">
          {translation.importSettings}
          <input
            data-testid="import-settings"
            type="file"
            accept="application/json,.json"
            onChange={(event) => {
              void importSettings(event.target.files?.[0]);
              event.target.value = "";
            }}
          />
        </label>
      </div>
      {status === "imported" && (
        <p className="setup-success" data-testid="settings-import-status">
          {translation.settingsImported}
        </p>
      )}
      {status === "failed" && (
        <p className="inline-warning" data-testid="settings-import-status">
          {translation.settingsImportFailed}
        </p>
      )}
    </section>
  );
}

interface TranslationHealth {
  upstreamRequests: number;
  upstreamSuccesses: number;
  upstreamSuccessRate: number | null;
  recentSuccessRate: number | null;
  deferredRequests: number;
  rateLimitedResponses: number;
  rateLimit: number | null;
  rateLimitRemaining: number | null;
}

function TranslationHealthSettings({ translation }: { translation: Translation }) {
  const [health, setHealth] = useState<TranslationHealth | null>(null);
  const [requestFailed, setRequestFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/v1/system/translation-health", { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return (await response.json()) as TranslationHealth;
      })
      .then(setHealth)
      .catch((error) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) {
          setRequestFailed(true);
        }
      });
    return () => controller.abort();
  }, []);

  return (
    <section className="metadata-settings" data-testid="translation-health">
      <h3>{translation.translationHealth}</h3>
      <p>{translation.translationHealthDescription}</p>
      {requestFailed ? (
        <p className="inline-warning">{translation.translationHealthUnavailable}</p>
      ) : health !== null && health.upstreamRequests > 0 ? (
        <>
          <p className="setup-success">
            {translation.translationHealthSummary(
              health.upstreamSuccessRate ?? 0,
              health.upstreamSuccesses,
              health.upstreamRequests,
            )}
          </p>
          {health.rateLimit !== null && health.rateLimitRemaining !== null && (
            <small>
              {translation.translationRateLimitSummary(health.rateLimitRemaining, health.rateLimit)}
            </small>
          )}
        </>
      ) : (
        <p>{translation.translationHealthNoRequests}</p>
      )}
    </section>
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
