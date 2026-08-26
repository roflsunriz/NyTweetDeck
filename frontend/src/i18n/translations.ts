import type { ColumnKind, Locale, NavItemId, Theme } from "../model/layout";

export interface Translation {
  appName: string;
  addColumn: string;
  addColumnDescription: string;
  columnTarget: string;
  userTargetHint: string;
  listTargetHint: string;
  confirmAddColumn: string;
  back: string;
  editMenu: string;
  editMenuDescription: string;
  noColumns: string;
  noColumnsDescription: string;
  close: string;
  removeColumn: (title: string) => string;
  loginRequired: string;
  loginRequiredDescription: string;
  settings: string;
  accountSwitcher: string;
  selectAccount: string;
  activeAccount: string;
  noUnlockedAccounts: string;
  loading: string;
  retry: string;
  loadMore: string;
  noPosts: string;
  timelineLoadError: string;
  messageLoadError: string;
  noMessages: string;
  reply: string;
  repost: string;
  like: string;
  views: string;
  bookmark: string;
  share: string;
  downloadMedia: string;
  askGrok: string;
  postMenu: string;
  notInterested: string;
  followUser: string;
  manageLists: string;
  muteUser: string;
  blockUser: string;
  postActivity: string;
  embedPost: string;
  reportPost: string;
  requestCommunityNote: string;
  composeTitle: string;
  postPlaceholder: string;
  publishPost: string;
  publishing: string;
  postFailed: string;
  postDetail: string;
  replies: string;
  closeDetail: string;
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
  accountVault: string;
  vaultPassphrase: string;
  confirmPassphrase: string;
  createVault: string;
  unlockVault: string;
  lockVault: string;
  vaultLocked: string;
  vaultUnlocked: string;
  passphraseRequirement: string;
  passphraseMismatch: string;
  vaultOperationError: string;
  accounts: string;
  noAccounts: string;
  loginAccount: string;
  continueLogin: string;
  selectLoginChoice: string;
  loginFailed: string;
  unsupportedLoginStep: string;
  localeName: Record<Locale, string>;
  themeName: Record<Theme, string>;
  nav: Record<NavItemId, string>;
  column: Record<ColumnKind, { title: string; description: string }>;
}

const translations: Partial<Record<Locale, Translation>> = {
  ja: {
    appName: "NyTweetDeck",
    addColumn: "カラムを追加",
    addColumnDescription: "表示したい情報の種類を選択してください。",
    columnTarget: "対象",
    userTargetHint: "ユーザーIDを入力",
    listTargetHint: "リストIDを入力",
    confirmAddColumn: "このカラムを追加",
    back: "戻る",
    editMenu: "メインメニューを編集",
    editMenuDescription: "表示する項目を選び、閉じた後にドラッグして並べ替えられます。",
    noColumns: "カラムがありません",
    noColumnsDescription: "追加ボタンから最初のカラムを作成できます。",
    close: "閉じる",
    removeColumn: (title) => `${title}を削除`,
    loginRequired: "ログインが必要です",
    loginRequiredDescription: "Vaultを解除し、使用するXアカウントを選択してください。",
    settings: "設定",
    accountSwitcher: "アカウント切り替え",
    selectAccount: "アカウントを選択",
    activeAccount: "使用中",
    noUnlockedAccounts: "Vaultを解除し、アカウントへログインしてください。",
    loading: "読み込み中…",
    retry: "再試行",
    loadMore: "さらに読み込む",
    noPosts: "表示するポストがありません。",
    timelineLoadError: "タイムラインを読み込めませんでした。",
    messageLoadError: "ダイレクトメッセージを読み込めませんでした。",
    noMessages: "表示するダイレクトメッセージがありません。",
    reply: "返信",
    repost: "リポスト",
    like: "いいね",
    views: "表示",
    bookmark: "履歴に保存",
    share: "共有",
    downloadMedia: "メディアをダウンロード",
    askGrok: "Grokに聞く",
    postMenu: "ポストメニュー",
    notInterested: "このポストに興味がない",
    followUser: "フォロー",
    manageLists: "リストから追加と削除",
    muteUser: "ミュート",
    blockUser: "ブロック",
    postActivity: "ポストアクティビティ",
    embedPost: "埋め込み",
    reportPost: "報告",
    requestCommunityNote: "コミュニティノートリクエスト",
    composeTitle: "ポストを作成",
    postPlaceholder: "いまどうしてる？",
    publishPost: "ポストする",
    publishing: "送信中…",
    postFailed: "ポストを送信できませんでした。",
    postDetail: "ポストの詳細",
    replies: "返信",
    closeDetail: "詳細を閉じる",
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
    accountVault: "アカウントVault",
    vaultPassphrase: "Vaultパスフレーズ",
    confirmPassphrase: "パスフレーズを確認",
    createVault: "Vaultを作成",
    unlockVault: "Vaultを解除",
    lockVault: "Vaultをロック",
    vaultLocked: "ロック中",
    vaultUnlocked: "解除済み",
    passphraseRequirement:
      "12文字以上の専用パスフレーズを指定してください。Xのパスワードは使用しません。",
    passphraseMismatch: "確認用パスフレーズが一致しません。",
    vaultOperationError: "アカウントVaultを操作できませんでした。",
    accounts: "保存済みアカウント",
    noAccounts: "保存済みアカウントはありません。",
    loginAccount: "Xアカウントにログイン",
    continueLogin: "続ける",
    selectLoginChoice: "選択してください",
    loginFailed: "Xアカウントへログインできませんでした。設定と入力を確認してください。",
    unsupportedLoginStep: "この認証手順には現在の端末では対応できません。",
    localeName: {
      ja: "日本語",
      en: "English",
      zh: "中文",
      hi: "हिन्दी",
      es: "Español",
      fr: "Français",
      ar: "العربية",
      pt: "Português",
      bn: "বাংলা",
      ru: "Русский",
      ur: "اردو",
    },
    themeName: { system: "システム設定", light: "ライト", dark: "ダーク" },
    nav: {
      compose: "ポストを作成",
      search: "検索",
      home: "ホーム",
      notifications: "通知",
      messages: "ダイレクトメッセージ",
      trends: "トレンド",
      following: "フォローする",
      chat: "チャット",
      grok: "Grok",
      premium: "プレミアム",
      profile: "プロフィール",
      communities: "コミュニティ",
      creatorStudio: "クリエイタースタジオ",
      business: "ビジネス",
      ads: "広告",
      spaces: "スペースを作成",
    },
    column: {
      home: { title: "おすすめ", description: "おすすめのポストを時系列で表示" },
      notifications: { title: "通知", description: "返信やフォローなどの通知を表示" },
      history: { title: "履歴", description: "保存したポストや閲覧履歴を表示" },
      user: { title: "ユーザー", description: "指定したユーザーのポストを表示" },
      list: { title: "リスト", description: "アカウントに紐付くリストを表示" },
      messages: { title: "メッセージ", description: "信頼済みDM受信箱を表示" },
      trends: { title: "トレンド", description: "Exploreの話題とポストを表示" },
    },
  },
  en: {
    appName: "NyTweetDeck",
    addColumn: "Add column",
    addColumnDescription: "Choose the kind of information you want to display.",
    columnTarget: "Target",
    userTargetHint: "Enter a user ID",
    listTargetHint: "Enter a list ID",
    confirmAddColumn: "Add this column",
    back: "Back",
    editMenu: "Edit main menu",
    editMenuDescription: "Choose visible items, then drag them to reorder after closing.",
    noColumns: "No columns yet",
    noColumnsDescription: "Use the add button to create your first column.",
    close: "Close",
    removeColumn: (title) => `Remove ${title}`,
    loginRequired: "Sign-in required",
    loginRequiredDescription: "Unlock the vault and select the X account to use.",
    settings: "Settings",
    accountSwitcher: "Switch account",
    selectAccount: "Select account",
    activeAccount: "Active",
    noUnlockedAccounts: "Unlock the vault and sign in to an account.",
    loading: "Loading…",
    retry: "Retry",
    loadMore: "Load more",
    noPosts: "No posts to display.",
    timelineLoadError: "Could not load the timeline.",
    messageLoadError: "Could not load direct messages.",
    noMessages: "No direct messages to display.",
    reply: "Reply",
    repost: "Repost",
    like: "Like",
    views: "Views",
    bookmark: "Save to history",
    share: "Share",
    downloadMedia: "Download media",
    askGrok: "Ask Grok",
    postMenu: "Post menu",
    notInterested: "Not interested in this post",
    followUser: "Follow",
    manageLists: "Add or remove from Lists",
    muteUser: "Mute",
    blockUser: "Block",
    postActivity: "Post activity",
    embedPost: "Embed",
    reportPost: "Report",
    requestCommunityNote: "Request Community Note",
    composeTitle: "Compose post",
    postPlaceholder: "What is happening?",
    publishPost: "Post",
    publishing: "Posting…",
    postFailed: "Could not publish the post.",
    postDetail: "Post details",
    replies: "Replies",
    closeDetail: "Close details",
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
    accountVault: "Account vault",
    vaultPassphrase: "Vault passphrase",
    confirmPassphrase: "Confirm passphrase",
    createVault: "Create vault",
    unlockVault: "Unlock vault",
    lockVault: "Lock vault",
    vaultLocked: "Locked",
    vaultUnlocked: "Unlocked",
    passphraseRequirement:
      "Use a dedicated passphrase of at least 12 characters. Do not use your X password.",
    passphraseMismatch: "The confirmation passphrase does not match.",
    vaultOperationError: "Could not operate the account vault.",
    accounts: "Saved accounts",
    noAccounts: "No saved accounts yet.",
    loginAccount: "Sign in to X",
    continueLogin: "Continue",
    selectLoginChoice: "Select an option",
    loginFailed: "Could not sign in to X. Check setup and your input.",
    unsupportedLoginStep: "This authentication step is not supported on this device.",
    localeName: {
      ja: "日本語",
      en: "English",
      zh: "中文",
      hi: "हिन्दी",
      es: "Español",
      fr: "Français",
      ar: "العربية",
      pt: "Português",
      bn: "বাংলা",
      ru: "Русский",
      ur: "اردو",
    },
    themeName: { system: "System", light: "Light", dark: "Dark" },
    nav: {
      compose: "Compose",
      search: "Search",
      home: "Home",
      notifications: "Notifications",
      messages: "Direct messages",
      trends: "Trends",
      following: "Following",
      chat: "Chat",
      grok: "Grok",
      premium: "Premium",
      profile: "Profile",
      communities: "Communities",
      creatorStudio: "Creator Studio",
      business: "Business",
      ads: "Ads",
      spaces: "Create a Space",
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
      messages: { title: "Messages", description: "Show the trusted DM inbox" },
      trends: { title: "Trends", description: "Show Explore topics and posts" },
    },
  },
};

export function translate(locale: Locale): Translation {
  const translation = translations[locale] ?? translations.en ?? translations.ja;
  if (translation === undefined) {
    throw new Error("翻訳辞書がありません。");
  }
  return translation;
}
