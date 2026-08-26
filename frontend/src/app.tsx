import {
  BadgeCheck,
  Bell,
  BriefcaseBusiness,
  CircleUserRound,
  Clapperboard,
  Flame,
  Home,
  Mail,
  Megaphone,
  MessageCircleMore,
  Minus,
  PenLine,
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
import { useEffect, useMemo, useState } from "react";
import { AddColumnDialog } from "./components/add-column-dialog";
import { AccountSwitcherDialog } from "./components/account-switcher-dialog";
import { ComposerDialog } from "./components/composer-dialog";
import { DirectMessageColumn } from "./components/direct-message-column";
import { MenuEditorDialog } from "./components/menu-editor-dialog";
import { LoginDialog } from "./components/login-dialog";
import { SettingsDialog } from "./components/settings-dialog";
import { TimelineColumn } from "./components/timeline-column";
import { translate } from "./i18n/translations";
import {
  type AppLayout,
  type ColumnKind,
  type Locale,
  loadLayout,
  moveItem,
  type NavItemId,
  saveLayout,
  rtlLocales,
  type Theme,
} from "./model/layout";

const navIcons: Record<NavItemId, LucideIcon> = {
  compose: PenLine,
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

function createColumnId(): string {
  return globalThis.crypto?.randomUUID?.() ?? `column-${Date.now()}-${Math.random()}`;
}

function resolveTheme(theme: Theme): "light" | "dark" {
  if (theme !== "system") {
    return theme;
  }
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

export function App() {
  const [layout, setLayout] = useState<AppLayout>(() => loadLayout(window.localStorage));
  const [dialog, setDialog] = useState<
    "accounts" | "columns" | "composer" | "login" | "menu" | "settings" | null
  >(null);
  const translation = useMemo(() => translate(layout.locale), [layout.locale]);

  useEffect(() => {
    saveLayout(window.localStorage, layout);
    document.documentElement.lang = layout.locale;
    document.documentElement.dir = rtlLocales.includes(layout.locale) ? "rtl" : "ltr";
    document.documentElement.dataset.theme = resolveTheme(layout.theme);
  }, [layout]);

  useEffect(() => {
    if (layout.theme !== "system") {
      return;
    }
    const mediaQuery = window.matchMedia("(prefers-color-scheme: light)");
    const updateTheme = () => {
      document.documentElement.dataset.theme = mediaQuery.matches ? "light" : "dark";
    };
    mediaQuery.addEventListener("change", updateTheme);
    return () => mediaQuery.removeEventListener("change", updateTheme);
  }, [layout.theme]);

  const addColumn = (kind: ColumnKind, target: string | null) => {
    setLayout((current) => ({
      ...current,
      columns: [...current.columns, { id: createColumnId(), kind, target }],
    }));
    setDialog(null);
  };

  const removeColumn = (id: string) => {
    setLayout((current) => ({
      ...current,
      columns: current.columns.filter((column) => column.id !== id),
    }));
  };

  const setLocale = (locale: Locale) => setLayout((current) => ({ ...current, locale }));
  const setTheme = (theme: Theme) => setLayout((current) => ({ ...current, theme }));
  const setNavigationItems = (navItems: NavItemId[]) =>
    setLayout((current) => ({ ...current, navItems }));
  const setActiveAccount = (activeAccountId: string) => {
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

  const activateNavigation = (item: NavItemId) => {
    if (item === "compose") {
      setDialog("composer");
    } else if (item === "home") {
      addColumn("home", null);
    } else if (item === "notifications") {
      addColumn("notifications", null);
    } else if (item === "search") {
      setDialog("columns");
    } else if (item === "messages") {
      addColumn("messages", null);
    } else if (item === "trends") {
      addColumn("trends", null);
    }
  };

  return (
    <div className="app-shell">
      <aside className="main-navigation" aria-label={translation.appName}>
        <div className="brand-mark" aria-hidden="true">
          N
        </div>
        <nav className="primary-actions">
          {layout.navItems.map((item) => {
            const Icon = navIcons[item];
            return (
              <button
                className="nav-button"
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
            type="button"
            aria-label={translation.accountSwitcher}
            onClick={() => setDialog("accounts")}
          >
            <CircleUserRound aria-hidden="true" size={22} />
          </button>
          <button
            className="nav-button"
            type="button"
            aria-label={translation.settings}
            onClick={() => setDialog("settings")}
          >
            <Settings aria-hidden="true" size={21} />
          </button>
        </div>
      </aside>

      <main className="deck" aria-live="polite">
        {layout.columns.length === 0 ? (
          <section className="empty-state">
            <button
              className="large-add-button"
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
                  draggable
                  onDragStart={(event) =>
                    event.dataTransfer.setData("text/plain", `column:${column.id}`)
                  }
                  onDragOver={(event) => event.preventDefault()}
                  onDrop={(event) => {
                    const source = event.dataTransfer.getData("text/plain");
                    if (source.startsWith("column:")) {
                      moveColumn(source.slice(7), column.id);
                    }
                  }}
                >
                  <header className="column-header">
                    <div>
                      <span className="column-kicker">NyTweetDeck</span>
                      <h2>
                        {column.target === null
                          ? columnText.title
                          : `${columnText.title}: ${column.target}`}
                      </h2>
                    </div>
                    <button
                      className="icon-button"
                      type="button"
                      aria-label={translation.removeColumn(columnText.title)}
                      onClick={() => removeColumn(column.id)}
                    >
                      <Minus aria-hidden="true" size={20} />
                    </button>
                  </header>
                  {column.kind === "messages" ? (
                    <DirectMessageColumn
                      accountId={layout.activeAccountId}
                      translation={translation}
                    />
                  ) : (
                    <TimelineColumn
                      column={column}
                      accountId={layout.activeAccountId}
                      translation={translation}
                    />
                  )}
                </article>
              );
            })}
            <button
              className="inline-add-column"
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
          onAdd={addColumn}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === "settings" && (
        <SettingsDialog
          translation={translation}
          locale={layout.locale}
          theme={layout.theme}
          onLocaleChange={setLocale}
          onThemeChange={setTheme}
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
          accountId={layout.activeAccountId}
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
  );
}
