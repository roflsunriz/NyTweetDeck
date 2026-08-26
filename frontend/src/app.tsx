import {
  Bell,
  CircleUserRound,
  Flame,
  Home,
  Mail,
  Minus,
  PenLine,
  Plus,
  Search,
  Settings,
  type LucideIcon,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { AddColumnDialog } from "./components/add-column-dialog";
import { SettingsDialog } from "./components/settings-dialog";
import { translate } from "./i18n/translations";
import {
  type AppLayout,
  type ColumnKind,
  type Locale,
  loadLayout,
  type NavItemId,
  saveLayout,
  type Theme,
} from "./model/layout";

const navIcons: Record<NavItemId, LucideIcon> = {
  compose: PenLine,
  search: Search,
  home: Home,
  notifications: Bell,
  messages: Mail,
  trends: Flame,
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
  const [dialog, setDialog] = useState<"columns" | "settings" | null>(null);
  const translation = useMemo(() => translate(layout.locale), [layout.locale]);

  useEffect(() => {
    saveLayout(window.localStorage, layout);
    document.documentElement.lang = layout.locale;
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

  const addColumn = (kind: ColumnKind) => {
    setLayout((current) => ({
      ...current,
      columns: [...current.columns, { id: createColumnId(), kind }],
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
                aria-label={translation.nav[item]}
              >
                <Icon aria-hidden="true" size={21} strokeWidth={1.9} />
              </button>
            );
          })}
          <button
            className="nav-button add-nav-button"
            type="button"
            aria-label={translation.addColumn}
            onClick={() => setDialog("columns")}
          >
            <Plus aria-hidden="true" size={22} />
          </button>
        </nav>
        <div className="secondary-actions">
          <button className="nav-button" type="button" aria-label={translation.accountSwitcher}>
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
                <article className="deck-column" key={column.id} data-column-kind={column.kind}>
                  <header className="column-header">
                    <div>
                      <span className="column-kicker">NyTweetDeck</span>
                      <h2>{columnText.title}</h2>
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
                  <div className="column-empty-content">
                    <CircleUserRound aria-hidden="true" size={31} strokeWidth={1.4} />
                    <strong>{translation.loginRequired}</strong>
                    <p>{translation.loginRequiredDescription}</p>
                  </div>
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
    </div>
  );
}
