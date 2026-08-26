import type { ColumnKind, Locale, NavItemId, Theme } from "../model/layout";

export interface Translation {
  appName: string;
  addColumn: string;
  addColumnDescription: string;
  noColumns: string;
  noColumnsDescription: string;
  close: string;
  removeColumn: (title: string) => string;
  loginRequired: string;
  loginRequiredDescription: string;
  settings: string;
  accountSwitcher: string;
  language: string;
  theme: string;
  localeName: Record<Locale, string>;
  themeName: Record<Theme, string>;
  nav: Record<NavItemId, string>;
  column: Record<ColumnKind, { title: string; description: string }>;
}

const translations: Record<Locale, Translation> = {
  ja: {
    appName: "NyTweetDeck",
    addColumn: "カラムを追加",
    addColumnDescription: "表示したい情報の種類を選択してください。",
    noColumns: "カラムがありません",
    noColumnsDescription: "追加ボタンから最初のカラムを作成できます。",
    close: "閉じる",
    removeColumn: (title) => `${title}を削除`,
    loginRequired: "ログインが必要です",
    loginRequiredDescription: "Xアカウント接続機能は、Android版APIの解析後にここへ接続されます。",
    settings: "設定",
    accountSwitcher: "アカウント切り替え",
    language: "表示言語",
    theme: "テーマ",
    localeName: { ja: "日本語", en: "English" },
    themeName: { system: "システム設定", light: "ライト", dark: "ダーク" },
    nav: {
      compose: "ポストを作成",
      search: "検索",
      home: "ホーム",
      notifications: "通知",
      messages: "ダイレクトメッセージ",
      trends: "トレンド",
    },
    column: {
      home: { title: "おすすめ", description: "おすすめのポストを時系列で表示" },
      notifications: { title: "通知", description: "返信やフォローなどの通知を表示" },
      history: { title: "履歴", description: "保存したポストや閲覧履歴を表示" },
      user: { title: "ユーザー", description: "指定したユーザーのポストを表示" },
      list: { title: "リスト", description: "アカウントに紐付くリストを表示" },
    },
  },
  en: {
    appName: "NyTweetDeck",
    addColumn: "Add column",
    addColumnDescription: "Choose the kind of information you want to display.",
    noColumns: "No columns yet",
    noColumnsDescription: "Use the add button to create your first column.",
    close: "Close",
    removeColumn: (title) => `Remove ${title}`,
    loginRequired: "Sign-in required",
    loginRequiredDescription: "X account connectivity will appear here after Android API analysis.",
    settings: "Settings",
    accountSwitcher: "Switch account",
    language: "Language",
    theme: "Theme",
    localeName: { ja: "日本語", en: "English" },
    themeName: { system: "System", light: "Light", dark: "Dark" },
    nav: {
      compose: "Compose",
      search: "Search",
      home: "Home",
      notifications: "Notifications",
      messages: "Direct messages",
      trends: "Trends",
    },
    column: {
      home: { title: "For you", description: "Show recommended posts in chronological order" },
      notifications: {
        title: "Notifications",
        description: "Show replies, follows, and other alerts",
      },
      history: { title: "History", description: "Show saved posts and viewing history" },
      user: { title: "User", description: "Show posts from a selected user" },
      list: { title: "List", description: "Show a list connected to your account" },
    },
  },
};

export function translate(locale: Locale): Translation {
  return translations[locale] ?? translations.ja;
}
