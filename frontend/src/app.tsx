import {
  ArrowUpToLine,
  BadgeCheck,
  Bell,
  BriefcaseBusiness,
  CircleUserRound,
  Clapperboard,
  Flame,
  GripVertical,
  Home,
  Mail,
  Megaphone,
  Menu,
  MessageCircleMore,
  Minus,
  Pencil,
  Plus,
  Radio,
  Search,
  Settings,
  Sparkles,
  UserPlus,
  UserRound,
  Users,
  type LucideIcon,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { AddColumnDialog } from "./components/add-column-dialog";
import { AccountSwitcherDialog } from "./components/account-switcher-dialog";
import { ComposerDialog } from "./components/composer-dialog";
import { DirectMessageColumn } from "./components/direct-message-column";
import { MenuEditorDialog } from "./components/menu-editor-dialog";
import { LoginDialog } from "./components/login-dialog";
import { NotificationsColumn } from "./components/notifications-column";
import { PostTranslationProvider } from "./components/post-translation-context";
import { SettingsDialog } from "./components/settings-dialog";
import { TimelineColumn } from "./components/timeline-column";
import { TrendsColumn } from "./components/trends-column";
import { translate } from "./i18n/translations";
import { fetchWithTimeout } from "./model/fetch-with-timeout";
import {
  type AppLayout,
  type ColumnSort,
  type ColumnKind,
  type Locale,
  moveItem,
  type NavItemId,
  rememberTrendSearch,
  rtlLocales,
  supportedLocales,
  type Theme,
} from "./model/layout";
import { useSharedLayout } from "./model/use-shared-layout";
import { useListCandidates } from "./model/use-list-candidates";
import { TimelineCacheProvider } from "./model/timeline-cache";

const navIcons: Record<NavItemId, LucideIcon> = {
  compose: Pencil,
  search: Search,
  home: Home,
  notifications: Bell,
  messages: Mail,
  trends: Flame,
  following: UserPlus,
  chat: MessageCircleMore,
  grok: Sparkles,
  premium: BadgeCheck,
  profile: UserRound,
  communities: Users,
  creatorStudio: Clapperboard,
  business: BriefcaseBusiness,
  ads: Megaphone,
  spaces: Radio,
};

const externalNavigation: Partial<Record<NavItemId, string>> = {
  chat: "https://x.com/i/chat",
  grok: "https://x.com/i/grok",
  premium: "https://x.com/i/premium_sign_up",
  communities: "https://x.com/i/communities",
  creatorStudio: "https://business.x.com/en/products/media-studio",
  business: "https://business.x.com",
  ads: "https://ads.x.com",
  spaces: "https://x.com/i/spaces/start",
};

function createColumnId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `column-${Date.now()}-${Math.random()}`;
}

function resolveTheme(theme: Theme): "light" | "dark" {
  if (theme !== "system") {
    return theme;
  }
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

function resolveBrowserLocale(): Locale {
  const language = navigator.language.split("-")[0];
  return supportedLocales.includes(language as Locale) ? (language as Locale) : "en";
}

function isTimelineColumn(kind: ColumnKind): boolean {
  return kind !== "messages" && kind !== "notifications" && kind !== "trends";
}

const navigationAutoHideDelayMilliseconds = 3_000;

export function App() {
  return (
    <TimelineCacheProvider>
      <AppContent />
    </TimelineCacheProvider>
  );
}

function AppContent() {
  const {
    layout,
    error: sharedLayoutError,
    setLayout,
    retry: retrySharedLayout,
  } = useSharedLayout();
  const [accountIds, setAccountIds] = useState<string[] | null>(null);
  const browserLocale = useRef(resolveBrowserLocale()).current;
  const accountInitializationStarted = useRef(false);
  const [dialog, setDialog] = useState<
    "accounts" | "columns" | "composer" | "login" | "menu" | "search" | "settings" | null
  >(null);
  const [temporaryNavigationVisible, setTemporaryNavigationVisible] = useState(false);
  const [navigationInteractionActive, setNavigationInteractionActive] = useState(false);
  const translation = useMemo(
    () => translate(layout?.locale ?? browserLocale),
    [browserLocale, layout?.locale],
  );

  const revealNavigation = () => {
    setTemporaryNavigationVisible(true);
  };

  useEffect(() => {
    if (layout?.display.showMainNavigation === true) {
      setTemporaryNavigationVisible(false);
    }
  }, [layout?.display.showMainNavigation]);

  useEffect(() => {
    if (
      layout?.display.showMainNavigation !== false ||
      !temporaryNavigationVisible ||
      navigationInteractionActive
    ) {
      return;
    }
    const timer = globalThis.setTimeout(
      () => setTemporaryNavigationVisible(false),
      navigationAutoHideDelayMilliseconds,
    );
    return () => globalThis.clearTimeout(timer);
  }, [layout?.display.showMainNavigation, navigationInteractionActive, temporaryNavigationVisible]);

  useEffect(() => {
    if (layout === null) return;
    document.documentElement.lang = layout.locale;
    document.documentElement.dir = rtlLocales.includes(layout.locale) ? "rtl" : "ltr";
    document.documentElement.dataset.theme = resolveTheme(layout.theme);
    document.documentElement.dataset.fontSize = layout.display.fontSize;
    document.documentElement.dataset.accent = layout.display.accentColor;
    document.documentElement.dataset.density = layout.display.density;
    document.documentElement.dataset.reduceMotion = String(layout.display.reduceMotion);
    document.documentElement.dataset.navigationPosition = layout.display.navigationPosition;
    document.documentElement.dataset.mainNavigationVisible = String(
      layout.display.showMainNavigation || temporaryNavigationVisible,
    );
  }, [layout, temporaryNavigationVisible]);

  const layoutTheme = layout?.theme;
  useEffect(() => {
    if (layoutTheme !== "system") {
      return;
    }
    const mediaQuery = window.matchMedia("(prefers-color-scheme: light)");
    const updateTheme = () => {
      document.documentElement.dataset.theme = mediaQuery.matches ? "light" : "dark";
    };
    mediaQuery.addEventListener("change", updateTheme);
    return () => mediaQuery.removeEventListener("change", updateTheme);
  }, [layoutTheme]);

  useEffect(() => {
    if (layout === null || accountInitializationStarted.current) return;
    accountInitializationStarted.current = true;
    const initialActiveAccountId = layout.activeAccountId;
    const controller = new AbortController();
    void fetchWithTimeout("/api/v1/accounts", { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return (await response.json()) as Array<{ accountId: string }>;
      })
      .then((accounts) => {
        const ids = accounts.map((account) => account.accountId);
        setAccountIds(ids);
        setLayout((current) => {
          const activeAccountId =
            current.activeAccountId !== null && ids.includes(current.activeAccountId)
              ? current.activeAccountId
              : (ids[0] ?? null);
          return activeAccountId === current.activeAccountId
            ? current
            : { ...current, activeAccountId };
        });
        if (ids.length === 0 && initialActiveAccountId !== null) {
          setDialog("accounts");
        }
      })
      .catch((error) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) {
          setAccountIds([]);
          if (initialActiveAccountId !== null) {
            setDialog("accounts");
          }
        }
      });
    return () => controller.abort();
  }, [layout, setLayout]);

  const selectedSharedAccountId = layout?.activeAccountId;
  useEffect(() => {
    if (
      selectedSharedAccountId === null ||
      selectedSharedAccountId === undefined ||
      accountIds === null ||
      accountIds.includes(selectedSharedAccountId)
    ) {
      return;
    }
    const controller = new AbortController();
    void fetchWithTimeout("/api/v1/accounts", { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return (await response.json()) as Array<{ accountId: string }>;
      })
      .then((accounts) => {
        const ids = accounts.map((account) => account.accountId);
        setAccountIds(ids);
        if (!ids.includes(selectedSharedAccountId)) {
          setLayout((current) => ({ ...current, activeAccountId: ids[0] ?? null }));
        }
      })
      .catch((refreshError) => {
        if (!(refreshError instanceof DOMException && refreshError.name === "AbortError")) {
          setAccountIds([]);
        }
      });
    return () => controller.abort();
  }, [accountIds, selectedSharedAccountId, setLayout]);

  const activeAccountId =
    layout?.activeAccountId !== null &&
    layout?.activeAccountId !== undefined &&
    accountIds?.includes(layout.activeAccountId) === true
      ? layout.activeAccountId
      : null;
  const listCandidates = useListCandidates(accountIds, activeAccountId);

  const addColumn = (kind: ColumnKind, target: string | null, label: string | null = null) => {
    const column = { id: createColumnId(), kind, target, label, sort: "latest" as const };
    setLayout((current) =>
      current.columns.some((item) => item.id === column.id)
        ? current
        : { ...current, columns: [...current.columns, column] },
    );
    setDialog(null);
  };

  const removeColumn = (id: string) => {
    setLayout((current) => ({
      ...current,
      columns: current.columns.filter((column) => column.id !== id),
    }));
  };

  const setLocale = (locale: Locale) => setLayout((current) => ({ ...current, locale }));
  const setTranslationLocale = (translationLocale: Locale) =>
    setLayout((current) => ({ ...current, translationLocale }));
  const setTheme = (theme: Theme) => setLayout((current) => ({ ...current, theme }));
  const setDisplay = (display: AppLayout["display"]) =>
    setLayout((current) => ({ ...current, display }));
  const setAutoTranslatePosts = (autoTranslatePosts: boolean) =>
    setLayout((current) => ({
      ...current,
      display: { ...current.display, autoTranslatePosts },
    }));
  const setReplySort = (replySort: AppLayout["replySort"]) =>
    setLayout((current) => ({ ...current, replySort }));
  const setColumnSort = (columnId: string, sort: ColumnSort) =>
    setLayout((current) => ({
      ...current,
      columns: current.columns.map((column) =>
        column.id === columnId ? { ...column, sort } : column,
      ),
    }));
  const setNavigationItems = (navItems: NavItemId[]) =>
    setLayout((current) => ({ ...current, navItems }));
  const setActiveAccount = (activeAccountId: string) => {
    setAccountIds((current) =>
      current?.includes(activeAccountId) === true ? current : [...(current ?? []), activeAccountId],
    );
    setLayout((current) => ({ ...current, activeAccountId }));
    setDialog(null);
  };

  const moveNavigation = (source: NavItemId, target: NavItemId) => {
    setLayout((current) => ({
      ...current,
      navItems: moveItem(
        current.navItems,
        current.navItems.indexOf(source),
        current.navItems.indexOf(target),
      ),
    }));
  };

  const moveColumn = (sourceId: string, targetId: string) => {
    setLayout((current) => ({
      ...current,
      columns: moveItem(
        current.columns,
        current.columns.findIndex((column) => column.id === sourceId),
        current.columns.findIndex((column) => column.id === targetId),
      ),
    }));
  };

  const setTrendFilter = (columnId: string, query: string) => {
    const target = query.length === 0 ? null : query;
    setLayout((current) => ({
      ...current,
      columns: current.columns.map((column) =>
        column.id === columnId && column.kind === "trends"
          ? { ...column, target, label: null }
          : column,
      ),
    }));
  };

  const rememberTrendFilter = (query: string) => {
    setLayout((current) => ({
      ...current,
      trendSearchHistory: rememberTrendSearch(current.trendSearchHistory, query),
    }));
  };

  const activateNavigation = (item: NavItemId) => {
    if (item === "compose") {
      setDialog("composer");
    } else if (item === "home") {
      addColumn("home", null);
    } else if (item === "notifications") {
      addColumn("notifications", null);
    } else if (item === "search") {
      setDialog("search");
    } else if (item === "messages") {
      addColumn("messages", null);
    } else if (item === "trends") {
      addColumn("trends", null);
    } else if (item === "following") {
      addColumn("following", null);
    } else if (item === "profile") {
      if (activeAccountId === null) {
        setDialog("accounts");
      } else {
        addColumn("user", activeAccountId);
      }
    } else {
      const url = externalNavigation[item];
      if (url !== undefined) {
        window.open(url, "_blank", "noopener,noreferrer");
      }
    }
  };

  if (layout === null) {
    return (
      <main className="shared-settings-status">
        <strong>
          {sharedLayoutError === null ? translation.loading : translation.sharedSettingsLoadError}
        </strong>
        {sharedLayoutError !== null && (
          <button className="secondary-button" type="button" onClick={retrySharedLayout}>
            {translation.retry}
          </button>
        )}
      </main>
    );
  }

  const navigationVisible = layout.display.showMainNavigation || temporaryNavigationVisible;

  return (
    <PostTranslationProvider
      value={{
        locale: layout.locale,
        translationLocale: layout.translationLocale,
        autoTranslatePosts: layout.display.autoTranslatePosts,
        setAutoTranslatePosts,
        replySort: layout.replySort,
        setReplySort,
      }}
    >
      <div
        className="app-shell"
        data-navigation-position={layout.display.navigationPosition}
        data-main-navigation-mode={layout.display.showMainNavigation ? "always" : "auto-hide"}
        data-main-navigation-visible={String(navigationVisible)}
        data-navigation-interaction-active={String(navigationInteractionActive)}
      >
        {sharedLayoutError !== null && (
          <div className="shared-settings-warning" role="alert">
            <span>
              {sharedLayoutError === "conflict"
                ? translation.sharedSettingsConflict
                : translation.sharedSettingsSaveError}
            </span>
            <button type="button" onClick={retrySharedLayout}>
              {translation.retry}
            </button>
          </div>
        )}
        {navigationVisible ? (
          <aside
            className="main-navigation"
            aria-label={translation.appName}
            onMouseEnter={() => setNavigationInteractionActive(true)}
            onMouseMove={() => setNavigationInteractionActive(true)}
            onMouseLeave={() => setNavigationInteractionActive(false)}
            onFocus={() => setNavigationInteractionActive(true)}
            onBlur={(event) => {
              if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
                setNavigationInteractionActive(false);
              }
            }}
          >
            <nav className="primary-actions">
              {layout.navItems.map((item) => {
                const Icon = navIcons[item];
                return (
                  <button
                    className="nav-button"
                    data-nav-item={item}
                    type="button"
                    key={item}
                    draggable
                    aria-label={translation.nav[item]}
                    onClick={() => activateNavigation(item)}
                    onDragStart={(event) => event.dataTransfer.setData("text/plain", `nav:${item}`)}
                    onDragOver={(event) => event.preventDefault()}
                    onDrop={(event) => {
                      const source = event.dataTransfer.getData("text/plain");
                      if (source.startsWith("nav:")) {
                        moveNavigation(source.slice(4) as NavItemId, item);
                      }
                    }}
                  >
                    <Icon aria-hidden="true" size={21} strokeWidth={1.9} />
                  </button>
                );
              })}
              <button
                className="nav-button add-nav-button"
                data-action="edit-menu"
                type="button"
                aria-label={translation.addColumn}
                onClick={() => setDialog("menu")}
              >
                <Plus aria-hidden="true" size={22} />
              </button>
            </nav>
            <div className="secondary-actions">
              <button
                className="nav-button"
                data-action="switch-account"
                type="button"
                aria-label={translation.accountSwitcher}
                onClick={() => setDialog("accounts")}
              >
                <CircleUserRound aria-hidden="true" size={22} />
              </button>
              <button
                className="nav-button"
                data-action="open-settings"
                type="button"
                aria-label={translation.settings}
                onClick={() => setDialog("settings")}
              >
                <Settings aria-hidden="true" size={21} />
              </button>
            </div>
          </aside>
        ) : null}
        {!layout.display.showMainNavigation && !navigationVisible && (
          <>
            <div
              className="navigation-hover-zone"
              data-testid="navigation-hover-zone"
              aria-hidden="true"
              onMouseEnter={revealNavigation}
            />
            <button
              className="show-navigation-fab"
              type="button"
              aria-label={translation.revealMainNavigation}
              onClick={revealNavigation}
              onMouseEnter={revealNavigation}
              data-testid="show-main-navigation"
            >
              <Menu aria-hidden="true" size={18} />
            </button>
          </>
        )}

        <main className="deck" aria-live="polite">
          {layout.columns.length === 0 ? (
            <section className="empty-state">
              <button
                className="large-add-button"
                data-action="add-column"
                type="button"
                aria-label={translation.addColumn}
                onClick={() => setDialog("columns")}
              >
                <Plus aria-hidden="true" size={34} strokeWidth={1.5} />
              </button>
              <h1>{translation.noColumns}</h1>
              <p>{translation.noColumnsDescription}</p>
            </section>
          ) : (
            <div className="column-track">
              {layout.columns.map((column) => {
                const columnText = translation.column[column.kind];
                return (
                  <article
                    className="deck-column"
                    key={column.id}
                    data-column-kind={column.kind}
                    onDragOver={(event) => event.preventDefault()}
                    onDrop={(event) => {
                      const source = event.dataTransfer.getData("text/plain");
                      if (source.startsWith("column:")) {
                        moveColumn(source.slice(7), column.id);
                      }
                    }}
                  >
                    <header className="column-header">
                      <button
                        className="column-drag-handle"
                        type="button"
                        draggable
                        aria-labelledby={`column-title-${column.id}`}
                        onDragStart={(event) =>
                          event.dataTransfer.setData("text/plain", `column:${column.id}`)
                        }
                      >
                        <GripVertical aria-hidden="true" size={16} />
                      </button>
                      <div className="column-header-main">
                        <span className="column-kicker">NyTweetDeck</span>
                        <div className="column-title-row">
                          <h2 id={`column-title-${column.id}`}>
                            {column.target === null
                              ? columnText.title
                              : `${columnText.title}: ${column.label ?? column.target}`}
                          </h2>
                          {isTimelineColumn(column.kind) && (
                            <div className="column-timeline-controls">
                              <label className="column-sort-control">
                                <span>{translation.columnSort}</span>
                                <select
                                  data-testid={`column-sort-${column.id}`}
                                  value={column.sort ?? "latest"}
                                  onChange={(event) =>
                                    setColumnSort(column.id, event.target.value as ColumnSort)
                                  }
                                >
                                  <option value="latest">{translation.columnSortLatest}</option>
                                  <option value="top">{translation.columnSortTop}</option>
                                </select>
                              </label>
                              <button
                                className="icon-button column-scroll-top"
                                data-action="scroll-column-top"
                                type="button"
                                aria-label={translation.columnScrollTop}
                                title={translation.columnScrollTop}
                                onClick={(event) => {
                                  const timeline = event.currentTarget
                                    .closest(".deck-column")
                                    ?.querySelector<HTMLElement>(".timeline-content");
                                  if (timeline !== null && timeline !== undefined)
                                    timeline.scrollTop = 0;
                                }}
                              >
                                <ArrowUpToLine aria-hidden="true" size={16} />
                              </button>
                            </div>
                          )}
                        </div>
                      </div>
                      <button
                        className="icon-button"
                        data-action="remove-column"
                        type="button"
                        aria-label={translation.removeColumn(columnText.title)}
                        onClick={() => removeColumn(column.id)}
                      >
                        <Minus aria-hidden="true" size={20} />
                      </button>
                    </header>
                    {column.kind === "messages" ? (
                      <DirectMessageColumn
                        accountId={activeAccountId}
                        translation={translation}
                        subscriptionId={column.id}
                      />
                    ) : column.kind === "notifications" ? (
                      <NotificationsColumn
                        accountId={activeAccountId}
                        translation={translation}
                        display={layout.display}
                        locale={layout.locale}
                      />
                    ) : column.kind === "trends" ? (
                      <TrendsColumn
                        accountId={activeAccountId}
                        translation={translation}
                        filterQuery={column.target ?? ""}
                        searchHistory={layout.trendSearchHistory}
                        onFilterChange={(query) => setTrendFilter(column.id, query)}
                        onRememberFilter={rememberTrendFilter}
                        onSelect={(query) => addColumn("search", query)}
                      />
                    ) : (
                      <TimelineColumn
                        column={column}
                        accountId={activeAccountId}
                        translation={translation}
                        display={layout.display}
                        locale={layout.locale}
                        autoRefreshTimelines={layout.display.autoRefreshTimelines}
                      />
                    )}
                  </article>
                );
              })}
              <button
                className="inline-add-column"
                data-action="add-column"
                type="button"
                aria-label={translation.addColumn}
                onClick={() => setDialog("columns")}
              >
                <Plus aria-hidden="true" size={30} strokeWidth={1.5} />
                <span>{translation.addColumn}</span>
              </button>
            </div>
          )}
        </main>

        {dialog === "columns" && (
          <AddColumnDialog
            translation={translation}
            accountId={activeAccountId}
            listCandidates={listCandidates}
            onAdd={addColumn}
            onClose={() => setDialog(null)}
          />
        )}
        {dialog === "search" && (
          <AddColumnDialog
            translation={translation}
            accountId={activeAccountId}
            listCandidates={listCandidates}
            initialKind="search"
            onAdd={addColumn}
            onClose={() => setDialog(null)}
          />
        )}
        {dialog === "settings" && (
          <SettingsDialog
            translation={translation}
            locale={layout.locale}
            translationLocale={layout.translationLocale}
            theme={layout.theme}
            display={layout.display}
            layout={layout}
            onLocaleChange={setLocale}
            onTranslationLocaleChange={setTranslationLocale}
            onThemeChange={setTheme}
            onDisplayChange={setDisplay}
            onLayoutImport={setLayout}
            onClose={() => setDialog(null)}
          />
        )}
        {dialog === "accounts" && (
          <AccountSwitcherDialog
            translation={translation}
            activeAccountId={layout.activeAccountId}
            onSelect={setActiveAccount}
            onLogin={() => setDialog("login")}
            onClose={() => setDialog(null)}
          />
        )}
        {dialog === "login" && (
          <LoginDialog
            translation={translation}
            onComplete={setActiveAccount}
            onClose={() => setDialog(null)}
          />
        )}
        {dialog === "composer" && (
          <ComposerDialog
            translation={translation}
            accountId={activeAccountId}
            onClose={() => setDialog(null)}
          />
        )}
        {dialog === "menu" && (
          <MenuEditorDialog
            translation={translation}
            selected={layout.navItems}
            onChange={setNavigationItems}
            onClose={() => setDialog(null)}
          />
        )}
      </div>
    </PostTranslationProvider>
  );
}
