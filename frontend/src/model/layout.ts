export const layoutStorageKey = "nytweetdeck.layout";
export const layoutVersion = 11 as const;
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
export const replySorts = ["relevance", "recency", "likes"] as const;
export type ReplySort = (typeof replySorts)[number];
export const columnSorts = ["latest", "top"] as const;
export type ColumnSort = (typeof columnSorts)[number];
export const videoQualities = ["auto", "low", "medium", "high"] as const;
export type VideoQuality = (typeof videoQualities)[number];
export const navigationPositions = ["left", "bottom"] as const;
export type NavigationPosition = (typeof navigationPositions)[number];

export interface DisplayPreferences {
  fontSize: FontSize;
  accentColor: AccentColor;
  density: Density;
  reduceMotion: boolean;
  mediaPreview: boolean;
  videoAutoplay: boolean;
  videoLoop: boolean;
  videoVolume: number;
  autoTranslatePosts: boolean;
  autoRefreshTimelines: boolean;
  videoQuality: VideoQuality;
  navigationPosition: NavigationPosition;
  showMainNavigation: boolean;
}

export const defaultDisplayPreferences: DisplayPreferences = {
  fontSize: "default",
  accentColor: "blue",
  density: "comfortable",
  reduceMotion: false,
  mediaPreview: true,
  videoAutoplay: false,
  videoLoop: true,
  videoVolume: 100,
  autoTranslatePosts: true,
  autoRefreshTimelines: true,
  videoQuality: "auto",
  navigationPosition: "left",
  showMainNavigation: true,
};

export interface ColumnConfig {
  id: string;
  kind: ColumnKind;
  target: string | null;
  label: string | null;
  /** Omitted only for in-memory compatibility with pre-v9 test fixtures. */
  sort?: ColumnSort;
}

export interface AppLayout {
  version: typeof layoutVersion;
  columns: ColumnConfig[];
  navItems: NavItemId[];
  locale: Locale;
  translationLocale: Locale;
  theme: Theme;
  activeAccountId: string | null;
  replySort: ReplySort;
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
    translationLocale: "ja",
    theme: "system",
    activeAccountId: null,
    replySort: "relevance",
    display: { ...defaultDisplayPreferences },
    trendSearchHistory: [],
  };
}

export function migrateLegacyLayout(value: unknown): AppLayout | null {
  if (isAppLayout(value)) return value;
  if (isLegacyLayoutV10(value)) {
    return {
      ...value,
      version: layoutVersion,
      display: { ...value.display, showMainNavigation: true },
    };
  }
  if (isLegacyLayoutV9(value)) {
    return {
      ...value,
      version: layoutVersion,
      display: {
        ...value.display,
        navigationPosition: "left" as const,
        showMainNavigation: true,
      },
    };
  }
  if (!isLegacyLayoutV8(value)) return null;
  return {
    ...value,
    version: layoutVersion,
    translationLocale: value.locale,
    columns: value.columns.map((column) => ({ ...column, sort: column.sort ?? "latest" })),
    display: {
      ...value.display,
      autoRefreshTimelines: true,
      videoQuality: "auto",
      navigationPosition: "left" as const,
      showMainNavigation: true,
    },
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
    if (isLegacyLayoutV10(candidate)) {
      const migrated: AppLayout = {
        ...candidate,
        version: layoutVersion,
        display: { ...candidate.display, showMainNavigation: true },
      };
      saveLayout(storage, migrated);
      return migrated;
    }
    if (isLegacyLayoutV9(candidate)) {
      const migrated: AppLayout = {
        ...candidate,
        version: layoutVersion,
        display: {
          ...candidate.display,
          navigationPosition: "left" as const,
          showMainNavigation: true,
        },
      };
      saveLayout(storage, migrated);
      return migrated;
    }
    if (isLegacyLayoutV8(candidate)) {
      const migrated: AppLayout = {
        ...candidate,
        version: layoutVersion,
        translationLocale: candidate.locale,
        columns: candidate.columns.map((column) => ({ ...column, sort: column.sort ?? "latest" })),
        display: {
          ...candidate.display,
          autoRefreshTimelines: true,
          videoQuality: "auto",
          navigationPosition: "left" as const,
          showMainNavigation: true,
        },
      };
      saveLayout(storage, migrated);
      return migrated;
    }
    if (isLegacyLayoutV7(candidate)) {
      const migrated: AppLayout = {
        ...candidate,
        version: layoutVersion,
        translationLocale: candidate.locale,
        replySort: "relevance",
        columns: candidate.columns.map((column) => ({ ...column, sort: column.sort ?? "latest" })),
        display: {
          ...candidate.display,
          autoRefreshTimelines: true,
          videoQuality: "auto",
          navigationPosition: "left" as const,
          showMainNavigation: true,
        },
      };
      saveLayout(storage, migrated);
      return migrated;
    }
    if (isLegacyLayoutV1(candidate)) {
      const migrated: AppLayout = {
        version: layoutVersion,
        columns: candidate.columns.map((column) => ({
          ...column,
          target: null,
          label: null,
          sort: "latest" as const,
        })),
        navItems: candidate.navItems,
        locale: candidate.locale,
        translationLocale: candidate.locale,
        theme: candidate.theme,
        activeAccountId: null,
        replySort: "relevance",
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
        translationLocale: candidate.locale,
        columns: candidate.columns.map((column) => ({
          ...column,
          label: null,
          sort: "latest" as const,
        })),
        replySort: "relevance",
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
        translationLocale: candidate.locale,
        columns: candidate.columns.map((column) => ({
          ...column,
          label: null,
          sort: "latest" as const,
        })),
        replySort: "relevance",
        display: {
          ...candidate.display,
          autoTranslatePosts: true,
          videoLoop: true,
          videoVolume: 100,
          autoRefreshTimelines: true,
          videoQuality: "auto",
          navigationPosition: "left" as const,
          showMainNavigation: true,
        },
        trendSearchHistory: [],
      };
      saveLayout(storage, migrated);
      return migrated;
    }
    if (isLegacyLayoutV4(candidate)) {
      const migrated: AppLayout = {
        ...candidate,
        version: layoutVersion,
        translationLocale: candidate.locale,
        columns: candidate.columns.map((column) => ({ ...column, sort: column.sort ?? "latest" })),
        replySort: "relevance",
        display: {
          ...candidate.display,
          autoTranslatePosts: true,
          videoLoop: true,
          videoVolume: 100,
          autoRefreshTimelines: true,
          videoQuality: "auto",
          navigationPosition: "left" as const,
          showMainNavigation: true,
        },
        trendSearchHistory: [],
      };
      saveLayout(storage, migrated);
      return migrated;
    }
    if (isLegacyLayoutV5(candidate)) {
      const migrated: AppLayout = {
        ...candidate,
        version: layoutVersion,
        translationLocale: candidate.locale,
        columns: candidate.columns.map((column) => ({ ...column, sort: column.sort ?? "latest" })),
        replySort: "relevance",
        display: {
          ...candidate.display,
          videoLoop: true,
          videoVolume: 100,
          autoRefreshTimelines: true,
          videoQuality: "auto",
          navigationPosition: "left" as const,
          showMainNavigation: true,
        },
        trendSearchHistory: [],
      };
      saveLayout(storage, migrated);
      return migrated;
    }
    if (isLegacyLayoutV6(candidate)) {
      const migrated: AppLayout = {
        ...candidate,
        version: layoutVersion,
        translationLocale: candidate.locale,
        replySort: "relevance",
        display: {
          ...candidate.display,
          videoLoop: true,
          videoVolume: 100,
          autoRefreshTimelines: true,
          videoQuality: "auto",
          navigationPosition: "left" as const,
          showMainNavigation: true,
        },
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

export function isAppLayout(value: unknown): value is AppLayout {
  if (!isRecord(value) || value.version !== layoutVersion) {
    return false;
  }
  if (
    !isLocale(value.locale) ||
    !isLocale(value.translationLocale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId, 200) ||
    !isReplySort(value.replySort) ||
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

function isLegacyLayoutV10(value: unknown): value is Omit<AppLayout, "version" | "display"> & {
  version: 10;
  display: LegacyDisplayPreferencesV10;
} {
  if (!isRecord(value) || value.version !== 10) return false;
  if (
    !isLocale(value.locale) ||
    !isLocale(value.translationLocale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId, 200) ||
    !isLegacyDisplayPreferencesV10(value.display) ||
    !isTrendSearchHistory(value.trendSearchHistory) ||
    !isReplySort(value.replySort)
  ) {
    return false;
  }
  if (!Array.isArray(value.navItems) || !value.navItems.every(isNavItemId)) return false;
  return Array.isArray(value.columns) && value.columns.every(isColumnConfig);
}

function isLegacyLayoutV9(value: unknown): value is Omit<AppLayout, "version" | "display"> & {
  version: 9;
  display: LegacyDisplayPreferencesV9;
} {
  if (!isRecord(value) || value.version !== 9) return false;
  if (
    !isLocale(value.locale) ||
    !isLocale(value.translationLocale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId, 200) ||
    !isLegacyDisplayPreferencesV9(value.display) ||
    !isTrendSearchHistory(value.trendSearchHistory) ||
    !isReplySort(value.replySort)
  ) {
    return false;
  }
  if (!Array.isArray(value.navItems) || !value.navItems.every(isNavItemId)) return false;
  return Array.isArray(value.columns) && value.columns.every(isColumnConfig);
}

function isLegacyLayoutV8(value: unknown): value is Omit<
  AppLayout,
  "version" | "translationLocale" | "display"
> & {
  version: 8;
  display: LegacyDisplayPreferencesV8;
} {
  if (!isRecord(value) || value.version !== 8) return false;
  if (
    !isLocale(value.locale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId, 200) ||
    !isLegacyDisplayPreferencesV8(value.display) ||
    !isTrendSearchHistory(value.trendSearchHistory) ||
    !isReplySort(value.replySort)
  ) {
    return false;
  }
  if (!Array.isArray(value.navItems) || !value.navItems.every(isNavItemId)) return false;
  return Array.isArray(value.columns) && value.columns.every(isColumnConfig);
}

function isLegacyLayoutV7(value: unknown): value is Omit<
  AppLayout,
  "version" | "translationLocale" | "replySort" | "display"
> & {
  version: 7;
  display: LegacyDisplayPreferencesV8;
} {
  if (!isRecord(value) || value.version !== 7) return false;
  if (
    !isLocale(value.locale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId, 200) ||
    !isLegacyDisplayPreferencesV8(value.display) ||
    !isTrendSearchHistory(value.trendSearchHistory)
  ) {
    return false;
  }
  if (!Array.isArray(value.navItems) || !value.navItems.every(isNavItemId)) return false;
  return Array.isArray(value.columns) && value.columns.every(isColumnConfig);
}

function isLegacyLayoutV5(value: unknown): value is Omit<
  AppLayout,
  "version" | "translationLocale" | "trendSearchHistory" | "display" | "replySort"
> & {
  version: 5;
  display: LegacyDisplayPreferencesV5;
} {
  if (!isRecord(value) || value.version !== 5) {
    return false;
  }
  if (
    !isLocale(value.locale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId, 200) ||
    !isLegacyDisplayPreferencesV5(value.display)
  ) {
    return false;
  }
  if (!Array.isArray(value.navItems) || !value.navItems.every(isNavItemId)) {
    return false;
  }
  return Array.isArray(value.columns) && value.columns.every(isColumnConfig);
}

function isLegacyLayoutV6(value: unknown): value is Omit<
  AppLayout,
  "version" | "translationLocale" | "display" | "replySort"
> & {
  version: 6;
  display: LegacyDisplayPreferencesV5;
} {
  if (!isRecord(value) || value.version !== 6) {
    return false;
  }
  if (
    !isLocale(value.locale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId, 200) ||
    !isLegacyDisplayPreferencesV5(value.display) ||
    !isTrendSearchHistory(value.trendSearchHistory)
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
  "version" | "translationLocale" | "columns" | "display" | "trendSearchHistory" | "replySort"
> & {
  version: 3;
  columns: Array<Omit<ColumnConfig, "label">>;
  display: LegacyDisplayPreferencesV3;
} {
  if (!isRecord(value) || value.version !== 3) {
    return false;
  }
  if (
    !isLocale(value.locale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId, 200) ||
    !isLegacyDisplayPreferencesV3(value.display)
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
  "version" | "translationLocale" | "display" | "trendSearchHistory" | "replySort"
> & {
  version: 4;
  display: LegacyDisplayPreferencesV3;
} {
  if (!isRecord(value) || value.version !== 4) {
    return false;
  }
  if (
    !isLocale(value.locale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId, 200) ||
    !isLegacyDisplayPreferencesV3(value.display)
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
  "version" | "translationLocale" | "display" | "trendSearchHistory" | "replySort"
> & {
  version: 2;
} {
  if (!isRecord(value) || value.version !== 2) {
    return false;
  }
  if (
    !isLocale(value.locale) ||
    !isTheme(value.theme) ||
    !isNullableString(value.activeAccountId, 200)
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
    value.id.length <= 200 &&
    isColumnKind(value.kind) &&
    isNullableString(value.target, 500) &&
    isNullableString(value.label, 500) &&
    (value.sort === undefined || isColumnSort(value.sort))
  );
}

function isLegacyTargetedColumn(value: unknown): value is Omit<ColumnConfig, "label"> {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    value.id.length > 0 &&
    value.id.length <= 200 &&
    isColumnKind(value.kind) &&
    isNullableString(value.target, 500)
  );
}

function isLegacyLayoutV1(value: unknown): value is Omit<
  AppLayout,
  | "version"
  | "translationLocale"
  | "activeAccountId"
  | "display"
  | "trendSearchHistory"
  | "replySort"
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
        column.id.length <= 200 &&
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

function isColumnSort(value: unknown): value is ColumnSort {
  return typeof value === "string" && columnSorts.includes(value as ColumnSort);
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

function isReplySort(value: unknown): value is ReplySort {
  return typeof value === "string" && replySorts.includes(value as ReplySort);
}

function isVideoQuality(value: unknown): value is VideoQuality {
  return typeof value === "string" && videoQualities.includes(value as VideoQuality);
}

function isNavigationPosition(value: unknown): value is NavigationPosition {
  return typeof value === "string" && navigationPositions.includes(value as NavigationPosition);
}

function isDisplayPreferences(value: unknown): value is DisplayPreferences {
  const record = isRecord(value) ? value : null;
  return (
    isLegacyDisplayPreferencesV10(value) &&
    record !== null &&
    typeof record.showMainNavigation === "boolean"
  );
}

type LegacyDisplayPreferencesV10 = Omit<DisplayPreferences, "showMainNavigation">;

function isLegacyDisplayPreferencesV10(value: unknown): value is LegacyDisplayPreferencesV10 {
  const record = isRecord(value) ? value : null;
  return (
    isLegacyDisplayPreferencesV9(value) &&
    record !== null &&
    isNavigationPosition(record.navigationPosition)
  );
}

type LegacyDisplayPreferencesV9 = Omit<LegacyDisplayPreferencesV10, "navigationPosition">;

function isLegacyDisplayPreferencesV9(value: unknown): value is LegacyDisplayPreferencesV9 {
  const record = isRecord(value) ? value : null;
  return (
    isLegacyDisplayPreferencesV8(value) &&
    record !== null &&
    typeof record.autoRefreshTimelines === "boolean" &&
    isVideoQuality(record.videoQuality)
  );
}

type LegacyDisplayPreferencesV8 = LegacyDisplayPreferencesV5 & {
  videoLoop: boolean;
  videoVolume: number;
};

type LegacyDisplayPreferencesV5 = LegacyDisplayPreferencesV3 & {
  autoTranslatePosts: boolean;
};

function isLegacyDisplayPreferencesV5(value: unknown): value is LegacyDisplayPreferencesV5 {
  const record = isRecord(value) ? value : null;
  return (
    isLegacyDisplayPreferencesV3(value) &&
    record !== null &&
    typeof record.autoTranslatePosts === "boolean"
  );
}

function isLegacyDisplayPreferencesV8(value: unknown): value is LegacyDisplayPreferencesV8 {
  const record = isRecord(value) ? value : null;
  return (
    isLegacyDisplayPreferencesV5(value) &&
    record !== null &&
    typeof record.videoLoop === "boolean" &&
    typeof record.videoVolume === "number" &&
    Number.isInteger(record.videoVolume) &&
    record.videoVolume >= 0 &&
    record.videoVolume <= 100
  );
}

type LegacyDisplayPreferencesV3 = {
  fontSize: FontSize;
  accentColor: AccentColor;
  density: Density;
  reduceMotion: boolean;
  mediaPreview: boolean;
  videoAutoplay: boolean;
};

function isLegacyDisplayPreferencesV3(value: unknown): value is LegacyDisplayPreferencesV3 {
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

function isNullableString(value: unknown, maximumLength: number): value is string | null {
  return value === null || (typeof value === "string" && value.length <= maximumLength);
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
