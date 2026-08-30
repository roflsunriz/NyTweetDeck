import type {
  AccentColor,
  ColumnKind,
  Density,
  FontSize,
  Locale,
  NavItemId,
  Theme,
} from "../model/layout";
import { localeOverrides } from "./locale-overrides";

export interface Translation {
  appName: string;
  addColumn: string;
  addColumnDescription: string;
  columnTarget: string;
  userTargetHint: string;
  listTargetHint: string;
  searchTargetHint: string;
  listSearch: string;
  listSearchHint: string;
  yourLists: string;
  suggestedLists: string;
  noLists: string;
  userResolveError: string;
  listLoadError: string;
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
  loading: string;
  retry: string;
  loadMore: string;
  noPosts: string;
  filterPosts: string;
  filterAll: string;
  filterText: string;
  filterImages: string;
  filterVideos: string;
  filterExcludeReposts: string;
  noFilteredPosts: string;
  userProfile: string;
  profileLoadError: string;
  profileAll: string;
  profilePosts: string;
  profileHighlights: string;
  profileReplies: string;
  profileMedia: string;
  joinedAt: (date: string) => string;
  followingCount: string;
  followersCount: string;
  mutualFollowers: string;
  timelineLoadError: string;
  newPosts: string;
  showNewPosts: (count: number) => string;
  liveUpdateUnavailable: string;
  messageLoadError: string;
  noMessages: string;
  noTrends: string;
  trendLoadError: string;
  trends: string;
  trendFilterLabel: string;
  trendFilterPlaceholder: string;
  clearTrendFilter: string;
  noFilteredTrends: string;
  noNotifications: string;
  notificationLoadError: string;
  notifications: string;
  communityNote: string;
  communityNoteDetails: string;
  communityNoteLoadError: string;
  reply: string;
  replyingToPost: string;
  replyingTo: (username: string) => string;
  repost: string;
  repostedBy: (name: string) => string;
  quote: string;
  quotingPost: string;
  like: string;
  views: string;
  bookmark: string;
  share: string;
  downloadMedia: string;
  viewMedia: string;
  fullSizeImage: string;
  closeImage: string;
  previousImage: string;
  nextImage: string;
  zoomIn: string;
  zoomOut: string;
  resetImageView: string;
  imageViewerHelp: string;
  xArticle: string;
  openArticle: string;
  closeArticle: string;
  openOnX: string;
  articleLoadError: string;
  askGrok: string;
  autoTranslatePosts: string;
  enableAutoTranslation: string;
  disableAutoTranslation: string;
  showOriginal: string;
  showTranslation: string;
  translationLoading: string;
  translationRetryScheduled: (seconds: number) => string;
  translationFailed: string;
  translatedBy: (provider: string) => string;
  postMenu: string;
  notInterested: string;
  followUser: string;
  manageLists: string;
  listId: string;
  addToList: string;
  removeFromList: string;
  muteUser: string;
  blockUser: string;
  postActivity: string;
  embedPost: string;
  reportPost: string;
  requestCommunityNote: string;
  confirmBlock: string;
  userActionCompleted: string;
  userActionFailed: string;
  postActionFailed: string;
  composeTitle: string;
  postPlaceholder: string;
  publishPost: string;
  publishing: string;
  postFailed: string;
  postDetail: string;
  replies: string;
  replySort: string;
  replySortRelevance: string;
  replySortRecency: string;
  replySortLikes: string;
  possibleSpamReplies: (count: number) => string;
  togglePossibleSpamReplies: (expanded: boolean, count: number) => string;
  closeDetail: string;
  language: string;
  theme: string;
  displayAndAccessibility: string;
  fontSize: string;
  accentColor: string;
  density: string;
  reduceMotion: string;
  mediaPreview: string;
  videoAutoplay: string;
  videoLoop: string;
  videoVolume: string;
  apiMetadata: string;
  apiMetadataDescription: string;
  apiMetadataUpdate: string;
  apiMetadataCurrent: string;
  apiMetadataFallback: string;
  apiMetadataFailed: string;
  settingsTransfer: string;
  settingsTransferDescription: string;
  exportSettings: string;
  importSettings: string;
  settingsImported: string;
  settingsImportFailed: string;
  sharedSettingsLoadError: string;
  sharedSettingsSaveError: string;
  sharedSettingsConflict: string;
  translationHealth: string;
  translationHealthDescription: string;
  translationHealthNoRequests: string;
  translationHealthUnavailable: string;
  translationHealthSummary: (rate: number, successful: number, total: number) => string;
  translationRateLimitSummary: (remaining: number, limit: number) => string;
  saving: string;
  accounts: string;
  noAccounts: string;
  loginAccount: string;
  continueLogin: string;
  browserLoginInstructions: string;
  browserLoginCapture: string;
  browserLoginCapturing: string;
  browserLoginBrowserClosed: string;
  selectLoginChoice: string;
  loginFailed: string;
  unsupportedLoginStep: string;
  localeName: Record<Locale, string>;
  themeName: Record<Theme, string>;
  fontSizeName: Record<FontSize, string>;
  accentColorName: Record<AccentColor, string>;
  densityName: Record<Density, string>;
  nav: Record<NavItemId, string>;
  column: Record<ColumnKind, { title: string; description: string }>;
}

export type TranslationOverride = Partial<Translation>;

const translations: Partial<Record<Locale, Translation>> = {
  ja: {
    appName: "NyTweetDeck",
    addColumn: "カラムを追加",
    addColumnDescription: "表示したい情報の種類を選択してください。",
    columnTarget: "対象",
    userTargetHint: "ユーザー名（@から始まる名前）を入力",
    listTargetHint: "リストを検索",
    searchTargetHint: "検索語句を入力",
    listSearch: "リストを検索",
    listSearchHint: "リスト名やキーワードを入力",
    yourLists: "自分のリスト",
    suggestedLists: "おすすめ・検索結果",
    noLists: "選択できるリストがありません。",
    userResolveError: "そのXユーザーを確認できませんでした。",
    listLoadError: "リスト一覧を読み込めませんでした。",
    confirmAddColumn: "このカラムを追加",
    back: "戻る",
    editMenu: "メインメニューを編集",
    editMenuDescription: "表示する項目を選び、閉じた後にドラッグして並べ替えられます。",
    noColumns: "カラムがありません",
    noColumnsDescription: "追加ボタンから最初のカラムを作成できます。",
    close: "閉じる",
    removeColumn: (title) => `${title}を削除`,
    loginRequired: "ログインが必要です",
    loginRequiredDescription:
      "使用するXアカウントへログインするか、保存済みアカウントを選択してください。",
    settings: "設定",
    accountSwitcher: "アカウント切り替え",
    selectAccount: "アカウントを選択",
    activeAccount: "使用中",
    loading: "読み込み中…",
    retry: "再試行",
    loadMore: "さらに読み込む",
    noPosts: "表示するポストがありません。",
    filterPosts: "ポストを複数条件で絞り込む",
    filterAll: "すべて",
    filterText: "文字",
    filterImages: "画像",
    filterVideos: "動画",
    filterExcludeReposts: "リポストを除く",
    noFilteredPosts: "条件に一致するポストはありません。",
    userProfile: "ユーザープロフィール",
    profileLoadError: "ユーザープロフィールを読み込めませんでした。",
    profileAll: "すべて",
    profilePosts: "ポスト",
    profileHighlights: "ハイライト",
    profileReplies: "返信",
    profileMedia: "メディア",
    joinedAt: (date) => `${date}から利用しています`,
    followingCount: "フォロー中",
    followersCount: "フォロワー",
    mutualFollowers: "共通のフォロワー",
    timelineLoadError: "タイムラインを読み込めませんでした。",
    newPosts: "新規投稿:",
    showNewPosts: (count) => `${count}件の新規投稿を表示`,
    liveUpdateUnavailable: "リアルタイム更新へ接続できません。再接続を試行しています。",
    messageLoadError: "ダイレクトメッセージを読み込めませんでした。",
    noMessages: "表示するダイレクトメッセージがありません。",
    noTrends: "表示するトレンドがありません。",
    trendLoadError: "トレンドを読み込めませんでした。",
    trends: "トレンド",
    trendFilterLabel: "トレンドを絞り込む",
    trendFilterPlaceholder: "検索ワードを入力",
    clearTrendFilter: "トレンドの絞り込みを解除",
    noFilteredTrends: "検索ワードに一致するトレンドがありません。",
    noNotifications: "表示する通知がありません。",
    notificationLoadError: "通知を読み込めませんでした。",
    notifications: "通知",
    communityNote: "コミュニティノート",
    communityNoteDetails: "コミュニティノートの詳細",
    communityNoteLoadError: "コミュニティノートと対象ポストを読み込めませんでした。",
    reply: "返信",
    replyingToPost: "返信先のポスト",
    replyingTo: (username) => `返信先: @${username}`,
    repost: "リポスト",
    repostedBy: (name) => `${name}さんがリポスト`,
    quote: "引用",
    quotingPost: "引用するポストを表示",
    like: "いいね",
    views: "表示",
    bookmark: "履歴に保存",
    share: "共有",
    downloadMedia: "メディアをダウンロード",
    viewMedia: "メディアを表示",
    fullSizeImage: "画像をフルサイズで表示",
    closeImage: "画像を閉じる",
    previousImage: "前の画像",
    nextImage: "次の画像",
    zoomIn: "拡大",
    zoomOut: "縮小",
    resetImageView: "表示位置と倍率をリセット",
    imageViewerHelp: "ドラッグで移動、スクロールで拡大・縮小、Escでポスト詳細に戻ります。",
    xArticle: "Xの記事",
    openArticle: "記事を読む",
    closeArticle: "記事を閉じる",
    openOnX: "Xで記事を開く",
    articleLoadError: "記事の全文を読み込めませんでした。",
    askGrok: "Grokに聞く",
    autoTranslatePosts: "表示言語と異なるポストを自動翻訳",
    enableAutoTranslation: "すべてのカラムで自動翻訳をオンにする",
    disableAutoTranslation: "すべてのカラムで自動翻訳をオフにする",
    showOriginal: "原文を表示",
    showTranslation: "翻訳を表示",
    translationLoading: "表示範囲のポストをXで翻訳中…",
    translationRetryScheduled: (seconds) => `X翻訳を再試行します（約${seconds}秒後）`,
    translationFailed: "翻訳できませんでした。原文を表示しています。",
    translatedBy: (provider) => `${provider}による自動翻訳`,
    postMenu: "ポストメニュー",
    notInterested: "このポストに興味がない",
    followUser: "フォロー",
    manageLists: "リストから追加と削除",
    listId: "リストID",
    addToList: "リストに追加",
    removeFromList: "リストから削除",
    muteUser: "ミュート",
    blockUser: "ブロック",
    postActivity: "ポストアクティビティ",
    embedPost: "埋め込み",
    reportPost: "報告",
    requestCommunityNote: "コミュニティノートリクエスト",
    confirmBlock: "このユーザーをブロックしますか？",
    userActionCompleted: "完了",
    userActionFailed: "ユーザー操作に失敗しました。",
    postActionFailed: "ポスト操作に失敗しました。",
    composeTitle: "ポストを作成",
    postPlaceholder: "いまどうしてる？",
    publishPost: "ポストする",
    publishing: "送信中…",
    postFailed: "ポストを送信できませんでした。",
    postDetail: "ポストの詳細",
    replies: "返信",
    replySort: "返信の並び順",
    replySortRelevance: "関連度",
    replySortRecency: "最新",
    replySortLikes: "いいね",
    possibleSpamReplies: (count) => `スパムの可能性のあるリプライ (${count})`,
    togglePossibleSpamReplies: (expanded, count) =>
      `${count}件のスパムの可能性のあるリプライを${expanded ? "折り畳む" : "表示"}`,
    closeDetail: "詳細を閉じる",
    language: "表示言語",
    theme: "テーマ",
    displayAndAccessibility: "アクセシビリティ、表示、データ使用量",
    fontSize: "文字サイズ",
    accentColor: "色",
    density: "表示密度",
    reduceMotion: "動きを減らす",
    mediaPreview: "画像と動画のプレビューを表示",
    videoAutoplay: "動画を自動再生",
    videoLoop: "動画をループ再生",
    videoVolume: "動画の音量",
    apiMetadata: "X Web API定義",
    apiMetadataDescription:
      "queryIdとFeature定義はX公式Webから定期的に自動更新されます。失敗時は直前の検証済み定義を維持します。",
    apiMetadataUpdate: "今すぐ定義を更新",
    apiMetadataCurrent: "X公式Webの最新定義を使用中です。",
    apiMetadataFallback: "同梱された検証済み定義を使用中です。",
    apiMetadataFailed: "更新できませんでした。直前の検証済み定義を維持しています。",
    settingsTransfer: "設定のインポート・エクスポート",
    settingsTransferDescription:
      "メニュー、カラム、表示設定、検索履歴をJSONで移動します。アカウント情報や認証情報は含みません。",
    exportSettings: "設定をエクスポート",
    importSettings: "設定をインポート",
    settingsImported: "設定を読み込み、自動保存しました。",
    settingsImportFailed: "設定を読み込めませんでした。有効なNyTweetDeck設定JSONを選んでください。",
    sharedSettingsLoadError:
      "共有設定を読み込めませんでした。NyTweetDeckを再起動するか、再試行してください。",
    sharedSettingsSaveError:
      "共有設定を保存できませんでした。変更は他のアドレスへまだ反映されていません。",
    sharedSettingsConflict:
      "別のNyTweetDeck画面で設定が更新されたため、最新の共有設定を読み込みました。",
    translationHealth: "X自動翻訳の稼働状況",
    translationHealthDescription:
      "画面に近いポストだけを翻訳し、レート制限時は解除後に自動再試行します。値はNyTweetDeck起動後の集計です。",
    translationHealthNoRequests: "この起動中には、まだリアルタイム翻訳通信がありません。",
    translationHealthUnavailable:
      "翻訳の稼働状況を取得できませんでした。設定画面を開き直してください。",
    translationHealthSummary: (rate, successful, total) =>
      `通信成功率 ${rate}%（成功 ${successful} / 通信 ${total}）`,
    translationRateLimitSummary: (remaining, limit) => `X翻訳の残り利用枠 ${remaining} / ${limit}`,
    saving: "保存中…",
    accounts: "保存済みアカウント",
    noAccounts: "保存済みアカウントはありません。",
    loginAccount: "Xアカウントにログイン",
    continueLogin: "続ける",
    browserLoginInstructions:
      "専用ChromeでXへのログインを完了し、Chromeを閉じずに下のボタンを押してください。",
    browserLoginCapture: "Xへのログインが完了しました",
    browserLoginCapturing: "Xのログインセッションをローカルに保存しています。",
    browserLoginBrowserClosed: "専用Chromeが閉じられました。もう一度ログインを開始してください。",
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
    fontSizeName: { small: "小", default: "標準", large: "大" },
    accentColorName: {
      blue: "青",
      yellow: "黄",
      pink: "ピンク",
      purple: "紫",
      orange: "オレンジ",
      green: "緑",
    },
    densityName: { comfortable: "標準", compact: "コンパクト" },
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
      following: { title: "フォロー中", description: "フォロー中の最新ポストを表示" },
      search: { title: "検索", description: "指定した検索語句の最新ポストを表示" },
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
    userTargetHint: "Enter an X username (starting with @)",
    listTargetHint: "Search lists",
    searchTargetHint: "Enter a search query",
    listSearch: "Search lists",
    listSearchHint: "Enter a list name or keyword",
    yourLists: "Your lists",
    suggestedLists: "Suggested and search results",
    noLists: "No lists are available to select.",
    userResolveError: "Could not find that X user.",
    listLoadError: "Could not load lists.",
    confirmAddColumn: "Add this column",
    back: "Back",
    editMenu: "Edit main menu",
    editMenuDescription: "Choose visible items, then drag them to reorder after closing.",
    noColumns: "No columns yet",
    noColumnsDescription: "Use the add button to create your first column.",
    close: "Close",
    removeColumn: (title) => `Remove ${title}`,
    loginRequired: "Sign-in required",
    loginRequiredDescription: "Sign in to X or select the saved account to use.",
    settings: "Settings",
    accountSwitcher: "Switch account",
    selectAccount: "Select account",
    activeAccount: "Active",
    loading: "Loading…",
    retry: "Retry",
    loadMore: "Load more",
    noPosts: "No posts to display.",
    filterPosts: "Filter posts with multiple criteria",
    filterAll: "All",
    filterText: "Text",
    filterImages: "Images",
    filterVideos: "Videos",
    filterExcludeReposts: "Exclude reposts",
    noFilteredPosts: "No posts match this filter.",
    userProfile: "User profile",
    profileLoadError: "Could not load the user profile.",
    profileAll: "All",
    profilePosts: "Posts",
    profileHighlights: "Highlights",
    profileReplies: "Replies",
    profileMedia: "Media",
    joinedAt: (date) => `Joined ${date}`,
    followingCount: "Following",
    followersCount: "Followers",
    mutualFollowers: "Followed by people you follow",
    timelineLoadError: "Could not load the timeline.",
    newPosts: "New posts:",
    showNewPosts: (count) => `Show ${count} new posts`,
    liveUpdateUnavailable: "Real-time updates are unavailable. Reconnecting…",
    messageLoadError: "Could not load direct messages.",
    noMessages: "No direct messages to display.",
    noTrends: "No trends to display.",
    trendLoadError: "Could not load trends.",
    trends: "Trends",
    trendFilterLabel: "Filter trends",
    trendFilterPlaceholder: "Enter a search term",
    clearTrendFilter: "Clear trend filter",
    noFilteredTrends: "No trends match this search term.",
    noNotifications: "No notifications to display.",
    notificationLoadError: "Could not load notifications.",
    notifications: "Notifications",
    communityNote: "Community Note",
    communityNoteDetails: "Community Note details",
    communityNoteLoadError: "Could not load the Community Note and its post.",
    reply: "Reply",
    replyingToPost: "Replying to a post",
    replyingTo: (username) => `Replying to @${username}`,
    repost: "Repost",
    repostedBy: (name) => `${name} reposted`,
    quote: "Quote",
    quotingPost: "View the post being quoted",
    like: "Like",
    views: "Views",
    bookmark: "Save to history",
    share: "Share",
    downloadMedia: "Download media",
    viewMedia: "View media",
    fullSizeImage: "View full-size image",
    closeImage: "Close image",
    previousImage: "Previous image",
    nextImage: "Next image",
    zoomIn: "Zoom in",
    zoomOut: "Zoom out",
    resetImageView: "Reset image position and zoom",
    imageViewerHelp: "Drag to pan, scroll to zoom, and press Escape to return to post details.",
    xArticle: "X Article",
    openArticle: "Read article",
    closeArticle: "Close article",
    openOnX: "Open article on X",
    articleLoadError: "Could not load the full article.",
    askGrok: "Ask Grok",
    autoTranslatePosts: "Automatically translate posts that use another language",
    enableAutoTranslation: "Turn on automatic translation for every column",
    disableAutoTranslation: "Turn off automatic translation for every column",
    showOriginal: "Show original",
    showTranslation: "Show translation",
    translationLoading: "Translating nearby posts with X…",
    translationRetryScheduled: (seconds) => `Retrying X translation in about ${seconds}s`,
    translationFailed: "Translation failed. Showing the original post.",
    translatedBy: (provider) => `Automatically translated by ${provider}`,
    postMenu: "Post menu",
    notInterested: "Not interested in this post",
    followUser: "Follow",
    manageLists: "Add or remove from Lists",
    listId: "List ID",
    addToList: "Add to List",
    removeFromList: "Remove from List",
    muteUser: "Mute",
    blockUser: "Block",
    postActivity: "Post activity",
    embedPost: "Embed",
    reportPost: "Report",
    requestCommunityNote: "Request Community Note",
    confirmBlock: "Block this user?",
    userActionCompleted: "Done",
    userActionFailed: "The user action failed.",
    postActionFailed: "The post action failed.",
    composeTitle: "Compose post",
    postPlaceholder: "What is happening?",
    publishPost: "Post",
    publishing: "Posting…",
    postFailed: "Could not publish the post.",
    postDetail: "Post details",
    replies: "Replies",
    replySort: "Reply order",
    replySortRelevance: "Relevance",
    replySortRecency: "Latest",
    replySortLikes: "Likes",
    possibleSpamReplies: (count) => `Possible spam replies (${count})`,
    togglePossibleSpamReplies: (expanded, count) =>
      `${expanded ? "Collapse" : "Show"} ${count} possible spam replies`,
    closeDetail: "Close details",
    language: "Language",
    theme: "Theme",
    displayAndAccessibility: "Accessibility, display, and data usage",
    fontSize: "Font size",
    accentColor: "Color",
    density: "Display density",
    reduceMotion: "Reduce motion",
    mediaPreview: "Show image and video previews",
    videoAutoplay: "Autoplay videos",
    videoLoop: "Loop videos",
    videoVolume: "Video volume",
    apiMetadata: "X Web API metadata",
    apiMetadataDescription:
      "Query IDs and feature definitions update periodically from X's official web assets. The last verified definitions remain active if an update fails.",
    apiMetadataUpdate: "Update definitions now",
    apiMetadataCurrent: "Using the latest definitions from X's official web assets.",
    apiMetadataFallback: "Using the bundled verified definitions.",
    apiMetadataFailed: "Update failed. The last verified definitions remain active.",
    settingsTransfer: "Import or export settings",
    settingsTransferDescription:
      "Move menus, columns, display preferences, and search history as JSON. Account and credential data are never included.",
    exportSettings: "Export settings",
    importSettings: "Import settings",
    settingsImported: "Settings imported and saved automatically.",
    settingsImportFailed: "Import failed. Select a valid NyTweetDeck settings JSON file.",
    sharedSettingsLoadError: "Could not load shared settings. Restart NyTweetDeck or try again.",
    sharedSettingsSaveError:
      "Could not save shared settings. Changes are not yet available at other addresses.",
    sharedSettingsConflict:
      "Settings changed in another NyTweetDeck window, so the latest shared settings were loaded.",
    translationHealth: "X automatic translation status",
    translationHealthDescription:
      "Only nearby posts are translated. Rate-limited requests retry automatically after the limit resets. Values cover this NyTweetDeck session.",
    translationHealthNoRequests: "No real-time translation requests have run in this session yet.",
    translationHealthUnavailable:
      "Translation status could not be loaded. Close and reopen Settings to retry.",
    translationHealthSummary: (rate, successful, total) =>
      `Request success rate ${rate}% (${successful} successful / ${total} requests)`,
    translationRateLimitSummary: (remaining, limit) =>
      `X translation allowance remaining: ${remaining} / ${limit}`,
    saving: "Saving…",
    accounts: "Saved accounts",
    noAccounts: "No saved accounts yet.",
    loginAccount: "Sign in to X",
    continueLogin: "Continue",
    browserLoginInstructions:
      "Finish signing in to X in the dedicated Chrome window, keep it open, then use the button below.",
    browserLoginCapture: "I finished signing in to X",
    browserLoginCapturing: "Saving the X login session locally.",
    browserLoginBrowserClosed: "The dedicated Chrome window was closed. Start sign-in again.",
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
    fontSizeName: { small: "Small", default: "Default", large: "Large" },
    accentColorName: {
      blue: "Blue",
      yellow: "Yellow",
      pink: "Pink",
      purple: "Purple",
      orange: "Orange",
      green: "Green",
    },
    densityName: { comfortable: "Comfortable", compact: "Compact" },
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
      following: {
        title: "Following",
        description: "Show the latest posts from followed accounts",
      },
      search: { title: "Search", description: "Show the latest posts matching a query" },
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
  const exact = translations[locale];
  if (exact !== undefined) {
    return exact;
  }
  const base = translations.en ?? translations.ja;
  if (base === undefined) {
    throw new Error("翻訳辞書がありません。");
  }
  const override = localeOverrides[locale];
  if (override === undefined) {
    return base;
  }
  return {
    ...base,
    ...override,
    localeName: override.localeName ?? base.localeName,
    themeName: override.themeName ?? base.themeName,
    fontSizeName: override.fontSizeName ?? base.fontSizeName,
    accentColorName: override.accentColorName ?? base.accentColorName,
    densityName: override.densityName ?? base.densityName,
    nav: override.nav ?? base.nav,
    column: override.column ?? base.column,
  };
}
