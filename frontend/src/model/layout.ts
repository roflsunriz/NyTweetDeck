export const layoutStorageKey = "nytweetdeck.layout";
export const layoutVersion = 2 as const;

export const columnKinds = ["home", "notifications", "history", "user", "list"] as const;
export type ColumnKind = (typeof columnKinds)[number];

export const navItemIds = [
  "compose",
  "search",
  "home",
  "notifications",
  "messages",
  "trends",
] as const;
export type NavItemId = (typeof navItemIds)[number];
export type Locale = "ja" | "en";
export type Theme = "system" | "light" | "dark";

export interface ColumnConfig {
  id: string;
  kind: ColumnKind;
  target: string | null;
}

export interface AppLayout {
  version: typeof layoutVersion;
  columns: ColumnConfig[];
  navItems: NavItemId[];
  locale: Locale;
  theme: Theme;
  activeAccountId: string | null;
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
    navItems: [...navItemIds],
    locale: "ja",
    theme: "system",
    activeAccountId: null,
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
        columns: candidate.columns.map((column) => ({ ...column, target: null })),
        navItems: candidate.navItems,
        locale: candidate.locale,
        theme: candidate.theme,
        activeAccountId: null,
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

function isAppLayout(value: unknown): value is AppLayout {
  if (!isRecord(value) || value.version !== layoutVersion) {
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
  if (new Set(value.navItems).size !== value.navItems.length) {
    return false;
  }
  return Array.isArray(value.columns) && value.columns.every(isColumnConfig);
}

function isColumnConfig(value: unknown): value is ColumnConfig {
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
  "version" | "activeAccountId"
> & {
  version: 1;
  columns: Array<Omit<ColumnConfig, "target">>;
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
  return typeof value === "string" && navItemIds.includes(value as NavItemId);
}

function isLocale(value: unknown): value is Locale {
  return value === "ja" || value === "en";
}

function isTheme(value: unknown): value is Theme {
  return value === "system" || value === "light" || value === "dark";
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string";
}
