export const layoutStorageKey = "nytweetdeck.layout";
export const layoutVersion = 1 as const;

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
}

export interface AppLayout {
  version: typeof layoutVersion;
  columns: ColumnConfig[];
  navItems: NavItemId[];
  locale: Locale;
  theme: Theme;
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
  if (!isLocale(value.locale) || !isTheme(value.theme)) {
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
    isColumnKind(value.kind)
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
