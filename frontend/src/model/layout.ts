export const layoutStorageKey = "nytweetdeck.layout";
export const layoutVersion = 6 as const;
export const trendSearchHistoryLimit = 20;

export const columnKinds = [
  "home",
  "following",
  "search",
  "notifications",
  "history",
  "user",
  "list",
  "messages",
  "trends",
] as const;
export type ColumnKind = (typeof columnKinds)[number];

export const defaultNavItemIds = [
  "compose",
  "search",
  "home",
  "notifications",
  "messages",
  "trends",
] as const;
export const availableNavItemIds = [
  ...defaultNavItemIds,
  "following",
  "chat",
  "grok",
  "premium",
  "profile",
  "communities",
  "creatorStudio",
  "business",
  "ads",
  "spaces",
] as const;
export type NavItemId = (typeof availableNavItemIds)[number];
export const supportedLocales = [
  "ja",
  "en",
  "zh",
  "hi",
  "es",
  "fr",
  "ar",
  "pt",
  "bn",
  "ru",
  "ur",
] as const;
export type Locale = (typeof supportedLocales)[number];
export const rtlLocales: readonly Locale[] = ["ar", "ur"];
export type Theme = "system" | "light" | "dark";
export type FontSize = "small" | "default" | "large";
export type AccentColor = "blue" | "yellow" | "pink" | "purple" | "orange" | "green";
export type Density = "comfortable" | "compact";

export interface DisplayPreferences {
  fontSize: FontSize;
  accentColor: AccentColor;
  density: Density;
  reduceMotion: boolean;
  mediaPreview: boolean;
  videoAutoplay: boolean;
  autoTranslatePosts: boolean;
}

export const defaultDisplayPreferences: DisplayPreferences = {
  fontSize: "default",
  accentColor: "blue",
  density: "comfortable",
  reduceMotion: false,
  mediaPreview: true,
  videoAutoplay: false,
  autoTranslatePosts: true,
};

export interface ColumnConfig {
  id: string;
  kind: ColumnKind;
  target: string | null;
  label: string | null;
}

export interface AppLayout {
  version: typeof layoutVersion;
  columns: ColumnConfig[];
  navItems: NavItemId[];
  locale: Locale;
  theme: Theme;
  activeAccountId: string | null;
  display: DisplayPreferences;
  trendSearchHistory: string[];
}

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export function createDefaultLayout(): AppLayout {
  return {
    version: layoutVersion,
    columns: [],
    navItems: [...defaultNavItemIds],
    locale: "ja",
    theme: "system",
    activeAccountId: null,
    display: { ...defaultDisplayPreferences },
    trendSearchHistory: [],
  };
}

export function loadLayout(storage: StorageLike): AppLayout {
  const serialized = storage.getItem(layoutStorageKey);
  if (serialized === null) {
    return createDefaultLayout();
  }

  try {
    const candidate: unknown = JSON.parse(serialized);
    if (isAppLayout(candidate)) {
      return candidate;
    }
    if (isLegacyLayoutV1(candidate)) {
      const migrated: AppLayout = {
        version: layoutVersion,
        columns: candidate.columns.map((column) => ({ ...column, target: null, label: null })),
        navItems: candidate.navItems,
        locale: candidate.locale,
        theme: candidate.theme,
        activeAccountId: null,
        display: { ...defaultDisplayPreferences },
        trendSearchHistory: [],
      };
      saveLayout(storage, migrated);
      return migrated;
    }
    if (isLegacyLayoutV2(candidate)) {
      const migrated: AppLayout = {
        ...candidate,
        version: layoutVersion,
        columns: candidate.columns.map((column) => ({ ...column, label: null })),
        display: { ...defaultDisplayPreferences },
        trendSearchHistory: [],
      };
      saveLayout(storage, migrated);
      return migrated;
    }
    if (isLegacyLayoutV3(candidate)) {
      const migrated: AppLayout = {
        ...candidate,
        version: layoutVersion,
        columns: candidate.columns.map((column) => ({ ...column, label: null })),
        display: { ...candidate.display, autoTranslatePosts: true },
        trendSearchHistory: [],
      };
      saveLayout(storage, migrated);
      return migrated;
    }
    if (isLegacyLayoutV4(candidate)) {
      const migrated: AppLayout = {
        ...candidate,
        version: layoutVersion,
        display: { ...candidate.display, autoTranslatePosts: true },
        trendSearchHistory: [],
      };
      saveLayout(storage, migrated);
      return migrated;
    }
    if (isLegacyLayoutV5(candidate)) {
      const migrated: AppLayout = {
        ...candidate,
        version: layoutVersion,
        trendSearchHistory: [],
      };
      saveLayout(storage, migrated);
      return migrated;
    }
  } catch {
    // The invalid entry is removed below so a clean layout can be created.
  }

  storage.removeItem(layoutStorageKey);
  return createDefaultLayout();
}

export function saveLayout(storage: StorageLike, layout: AppLayout): void {
  storage.setItem(layoutStorageKey, JSON.stringify(layout));
}

export function moveItem<T>(items: readonly T[], fromIndex: number, toIndex: number): T[] {
  if (
    fromIndex < 0 ||
    toIndex < 0 ||
    fromIndex >= items.length ||
    toIndex >= items.length ||
    fromIndex === toIndex
  ) {
    return [...items];
  }
  const result = [...items];
  const [item] = result.splice(fromIndex, 1);
  if (item === undefined) {
    return [...items];
  }
  result.splice(toIndex, 0, item);
  return result;
}

export function rememberTrendSearch(history: readonly string[], query: string): string[] {
  const normalized = query.trim();
  if (normalized.length === 0) {
    return [...history];
  }
  const key = normalized.toLocaleLowerCase();
  const existing = history.find((item) => item.toLocaleLowerCase() === key);
  return [
    existing ?? normalized,
    ...history.filter((item) => item.toLocaleLowerCase() !== key),
  ].slice(0, trendSearchHistoryLimit);
}

function isAppLayout(value: unknown): value is AppLayout {
  if (!isRecord(value) || value.version !== layoutVersion) {
    return false;
  }
  if (
    !isLocale(value.locale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId) ||
    !isDisplayPreferences(value.display) ||
    !isTrendSearchHistory(value.trendSearchHistory)
  ) {
    return false;
  }
  if (!Array.isArray(value.navItems) || !value.navItems.every(isNavItemId)) {
    return false;
  }
  if (new Set(value.navItems).size !== value.navItems.length) {
    return false;
  }
  return Array.isArray(value.columns) && value.columns.every(isColumnConfig);
}

function isLegacyLayoutV5(value: unknown): value is Omit<
  AppLayout,
  "version" | "trendSearchHistory"
> & {
  version: 5;
} {
  if (!isRecord(value) || value.version !== 5) {
    return false;
  }
  if (
    !isLocale(value.locale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId) ||
    !isDisplayPreferences(value.display)
  ) {
    return false;
  }
  if (!Array.isArray(value.navItems) || !value.navItems.every(isNavItemId)) {
    return false;
  }
  return Array.isArray(value.columns) && value.columns.every(isColumnConfig);
}

function isLegacyLayoutV3(value: unknown): value is Omit<
  AppLayout,
  "version" | "columns" | "display" | "trendSearchHistory"
> & {
  version: 3;
  columns: Array<Omit<ColumnConfig, "label">>;
  display: LegacyDisplayPreferences;
} {
  if (!isRecord(value) || value.version !== 3) {
    return false;
  }
  if (
    !isLocale(value.locale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId) ||
    !isLegacyDisplayPreferences(value.display)
  ) {
    return false;
  }
  if (!Array.isArray(value.navItems) || !value.navItems.every(isNavItemId)) {
    return false;
  }
  return Array.isArray(value.columns) && value.columns.every(isLegacyTargetedColumn);
}

function isLegacyLayoutV4(value: unknown): value is Omit<
  AppLayout,
  "version" | "display" | "trendSearchHistory"
> & {
  version: 4;
  display: LegacyDisplayPreferences;
} {
  if (!isRecord(value) || value.version !== 4) {
    return false;
  }
  if (
    !isLocale(value.locale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId) ||
    !isLegacyDisplayPreferences(value.display)
  ) {
    return false;
  }
  if (!Array.isArray(value.navItems) || !value.navItems.every(isNavItemId)) {
    return false;
  }
  return Array.isArray(value.columns) && value.columns.every(isColumnConfig);
}

function isLegacyLayoutV2(value: unknown): value is Omit<
  AppLayout,
  "version" | "display" | "trendSearchHistory"
> & {
  version: 2;
} {
  if (!isRecord(value) || value.version !== 2) {
    return false;
  }
  if (
    !isLocale(value.locale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId)
  ) {
    return false;
  }
  if (!Array.isArray(value.navItems) || !value.navItems.every(isNavItemId)) {
    return false;
  }
  return Array.isArray(value.columns) && value.columns.every(isLegacyTargetedColumn);
}

function isColumnConfig(value: unknown): value is ColumnConfig {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    value.id.length > 0 &&
    isColumnKind(value.kind) &&
    isNullableString(value.target) &&
    isNullableString(value.label)
  );
}

function isLegacyTargetedColumn(value: unknown): value is Omit<ColumnConfig, "label"> {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    value.id.length > 0 &&
    isColumnKind(value.kind) &&
    isNullableString(value.target)
  );
}

function isLegacyLayoutV1(value: unknown): value is Omit<
  AppLayout,
  "version" | "activeAccountId" | "display" | "trendSearchHistory"
> & {
  version: 1;
  columns: Array<Omit<ColumnConfig, "target" | "label">>;
} {
  if (!isRecord(value) || value.version !== 1) {
    return false;
  }
  if (!isLocale(value.locale) || !isTheme(value.theme)) {
    return false;
  }
  if (!Array.isArray(value.navItems) || !value.navItems.every(isNavItemId)) {
    return false;
  }
  return (
    Array.isArray(value.columns) &&
    value.columns.every(
      (column) =>
        isRecord(column) &&
        typeof column.id === "string" &&
        column.id.length > 0 &&
        isColumnKind(column.kind),
    )
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isColumnKind(value: unknown): value is ColumnKind {
  return typeof value === "string" && columnKinds.includes(value as ColumnKind);
}

function isNavItemId(value: unknown): value is NavItemId {
  return typeof value === "string" && availableNavItemIds.includes(value as NavItemId);
}

function isLocale(value: unknown): value is Locale {
  return typeof value === "string" && supportedLocales.includes(value as Locale);
}

function isTheme(value: unknown): value is Theme {
  return value === "system" || value === "light" || value === "dark";
}

function isDisplayPreferences(value: unknown): value is DisplayPreferences {
  return (
    isLegacyDisplayPreferences(value) &&
    isRecord(value) &&
    typeof (value as Record<string, unknown>).autoTranslatePosts === "boolean"
  );
}

type LegacyDisplayPreferences = Omit<DisplayPreferences, "autoTranslatePosts">;

function isLegacyDisplayPreferences(value: unknown): value is LegacyDisplayPreferences {
  return (
    isRecord(value) &&
    ["small", "default", "large"].includes(String(value.fontSize)) &&
    ["blue", "yellow", "pink", "purple", "orange", "green"].includes(String(value.accentColor)) &&
    ["comfortable", "compact"].includes(String(value.density)) &&
    typeof value.reduceMotion === "boolean" &&
    typeof value.mediaPreview === "boolean" &&
    typeof value.videoAutoplay === "boolean"
  );
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}

function isTrendSearchHistory(value: unknown): value is string[] {
  return (
    Array.isArray(value) &&
    value.length <= trendSearchHistoryLimit &&
    value.every(
      (item) =>
        typeof item === "string" && item.length > 0 && item.length <= 100 && item.trim() === item,
    ) &&
    new Set(value.map((item) => item.toLocaleLowerCase())).size === value.length
  );
}
