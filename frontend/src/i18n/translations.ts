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
  xApiSetup: string;
  xApiVersion: string;
  ready: string;
  notReady: string;
  clientCredentials: string;
  deviceProfile: string;
  deviceModel: string;
  androidVersion: string;
  manufacturer: string;
  brand: string;
  product: string;
  securityPatch: string;
  saveDeviceProfile: string;
  saving: string;
  saved: string;
  setupLoadError: string;
  setupSaveError: string;
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
    xApiSetup: "Android API設定",
    xApiVersion: "解析済みAPI版",
    ready: "準備済み",
    notReady: "未設定",
    clientCredentials: "Androidクライアント資格情報",
    deviceProfile: "Android端末プロファイル",
    deviceModel: "端末モデル",
    androidVersion: "Androidバージョン",
    manufacturer: "メーカー",
    brand: "ブランド",
    product: "製品コード",
    securityPatch: "セキュリティパッチ日",
    saveDeviceProfile: "端末プロファイルを保存",
    saving: "保存中…",
    saved: "保存しました",
    setupLoadError: "Android API設定を読み込めませんでした。",
    setupSaveError: "Android端末プロファイルを保存できませんでした。",
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
    xApiSetup: "Android API setup",
    xApiVersion: "Analyzed API version",
    ready: "Ready",
    notReady: "Not configured",
    clientCredentials: "Android client credentials",
    deviceProfile: "Android device profile",
    deviceModel: "Device model",
    androidVersion: "Android version",
    manufacturer: "Manufacturer",
    brand: "Brand",
    product: "Product code",
    securityPatch: "Security patch date",
    saveDeviceProfile: "Save device profile",
    saving: "Saving…",
    saved: "Saved",
    setupLoadError: "Could not load Android API setup.",
    setupSaveError: "Could not save the Android device profile.",
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
