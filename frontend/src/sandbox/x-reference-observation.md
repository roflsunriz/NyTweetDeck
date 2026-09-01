# X公式Web意味契約の観測記録

## 目的

X公式Webの公開配信資産をGit管理外のcaptureへ隔離し、外部通信を遮断したBrowserContextで合成mockを与えて挙動を観測する。公式コードや視覚資産はNyTweetDeckへ取り込まず、観測可能な状態遷移と入出力だけを意味契約としてReact、Java、Kotlinの独自実装へ反映する。

初回の種として次の5領域から着手した。これは優先順であり、観測・比較・独自実装の対象範囲を限定しない。

1. 動画プレイヤー
2. 画像ビューア
3. リプライツリー
4. URL正規化とリンク化
5. アカウント名・アバター・メンションからアプリ内プロフィールへの遷移

最終対象は、X Web版で利用者が観測できる全surface、全コンテンツ型、全静的要素、全動的要素、全状態遷移、全入力方式、主要viewport・locale・theme・権限・データ条件である。既知一覧だけを全対象とせず、公式bundle名と実DOMの匿名構造を毎回自動棚卸しし、未分類要素をカタログへ追加する。

## NyTweetDeck固有の製品不変条件

X公式Webの意味契約より次を優先し、互換化を理由に削除、固定化、単一フィード化しない。

- 利用者が任意種類のカラムを任意数追加・削除・並べ替えでき、再起動後も復元する。
- TweetDeck型の左メインメニューを常設する。
- 左メインメニューの項目を追加・削除・並べ替えでき、配置を永続化する。
- 複数アカウントのログイン状態を保持し、自動選択、即時切替、アカウント別データ分離を維持する。

X Webが単一画面・単一アカウント前提で持つ状態は、NyTweetDeckではカラム単位または選択アカウント単位へ適応する。公式挙動との一致判定は、これらの外枠を保った内側のカード、メディア、会話、操作、遷移、エラー状態に対して行う。

## 2026-08-31 初回観測

### 取得

- 未ログインXホーム: 67資産、6,048,531 bytes、ポストカード0件
- ログイン済みXホーム: 120資産、9,542,848 bytes、読み込み後ポストカード4件
- 全要素inventory追加後のログイン済みXホーム: 131 JavaScript資産、10,251,510 bytes、読み込み後ポストカード4件
- 保存対象: `abs.twimg.com`の許可済みパスにあるJavaScript・CSS本文、queryとfragmentを除去したURL、status、MIME、bytes、SHA-256、取得日時
- 非保存対象: Cookie、Authorization、全header、HTML、DOM、スクリーンショット、ポスト本文、ユーザー名、ユーザーID

ログイン済みcaptureの全JavaScriptを静的走査し、メモリー上のde-minifyを上位8資産へ適用した。最初の語彙シグナルは動画43、画像ビューア2、返信ツリー1,235、URL正規化442、プロフィール遷移396件で、全要素inventory追加後は動画116、画像ビューア2、返信ツリー1,237、URL正規化515、プロフィール遷移399件だった。この件数は候補資産を絞るための索引であり、機能の存在、利用条件、正しい挙動を単独で証明しない。

全要素inventoryでは123種類のbundle tokenを自動発見した。上位には`Routes`、`video`、`Grok`、`HoverCard`、`Compose`、`HomeTimeline`、`UserProfile`、`VideoPlayerDefaultUI`、`Bookmarks`、`XChat`、`AppShell`、`Explore`、`TwitterArticles`、`Birdwatch`、`LiveEvent`、`TweetCurationActionMenu`、`MultiAccount`、`Notifications`が含まれる。これは既知カタログ外のsurface・要素を見つける索引であり、tokenがあるだけで利用可能または実装対象確定とは判定しない。

初期ホームDOMは本文や識別子を保存せず、タグ14種類、role 21種類、安全な固定`data-testid` 20種類、ARIA・disabled状態8分類を数えた。実測値にはリンク61、ボタン57、タブ5、article 4、画像25、video 1、`aria-selected=true` 1、disabled 6が含まれた。以後はsurfaceと操作状態ごとに同じ匿名inventoryを採取し、初期ホームだけを全体の代表として扱わない。

### 隔離実行基盤

- ログイン状態を継承しない新規BrowserContextを使用
- 割り当てた`127.0.0.1`の一時serverだけを許可
- 外部HTTP、HTTPS、WebSocket、FTPの失敗を実測
- Cookie、`localStorage`、`sessionStorage`が空であることを実測
- text、image、video、reply、account-linkの合成mock 5件をloopbackから投入して描画

この段階では隔離境界とmock搬送経路、および最初のURL entity公式関数までを検証済みである。次に動画、画像、返信、リンク描画、プロフィール遷移の公式module境界を静的に特定し、capture内だけで調査用exportまたはadapterを一時付加して隔離実行する。

### URL entity補完の公式関数実行

ログイン済みcaptureの公式`main`資産から、URL entity補完関数をメモリー上で抽出した。関数本体を変更せず、外部通信を全面遮断した新規BrowserContextで合成5ケースを実行し、次を確認した。

- `display_url`がnullishの場合は`url`へ補完する。
- `expanded_url`がnullishの場合も`url`へ補完する。
- 両方が与えられた場合は既存値を維持する。
- 空文字はnullishとして扱わず、そのまま維持する。
- いずれかがnullishのentityを診断対象とし、今回の5ケースでは3件を返した。
- 入力配列と`indices`を保持したまま、補完済みentity配列と診断配列を返す。

この契約はURL entityの欠落補完までを対象とする。本文からのentity分割、`display_url`の表示、`expanded_url`への遷移、`unwound_url`の採用、メディア・記事URLの本文除去、クリック伝播は別の公式境界として追加観測する。

NyTweetDeck WebとAndroidには、正規化後本文のHTTP(S) URLを末尾句読点・不釣り合いな閉じ括弧から分離してリンク化する共通規則を反映した。Webはリンク操作をカード詳細へ伝播させず、AndroidはCompose `LinkAnnotation.Url`を使用する。JavaとKotlinの通常ポスト・引用モデルは`display_url`と、`unwound_url`優先の安全な実遷移先を別々に保持し、表示名欠落時だけ実URLへフォールバックする。

### 複数surface・動的viewport matrix

変更操作を含まない静的route 12件を巡回し、12件すべてでログイン状態を維持してcaptureできた。9件は指定pathへ直接到達し、検索はExploreへ、履歴とDMは識別子を`:dynamic`へ伏せた内部routeへredirectされた。12 surfaceの統合inventoryはbundle token 161種類、role 23種類、安全な固定`data-testid` 20種類だった。

ホームを毎回読み直す動的scenarioを1440x900、768x1024、390x844で実行した。観測から次を一般規則として採用した。

- ポストoverflowとリポストは全幅でrouteを変えない非modal menuで、開くと`role=menu`とtriggerの`aria-expanded=true`が各1件増える。
- 両menuは全幅でEscape後にmenu 0件・expanded増分0件へ戻る。
- ポスト詳細と作者プロフィールは全幅でmodalを増やさず固有routeへ遷移する。
- 画像viewerはdesktop・tabletでは固有route上のmodal、phoneではmodalを持たない全画面routeになる。
- 作成画面はdesktop・tabletでは`/compose/post`上のmodal、phoneでは同routeの全画面表示になる。
- phoneのアカウント切替はhome routeを維持し、非modal dialogとexpanded triggerを各1件増やす。広幅では別の非modal構造を使うため、内部構造は追加観測する。
- phoneのMoreはSideNavと同じ操作点を持たず、未観測のモバイル導線として残す。

これらの契約とデスクトップ・Androidの反映状態は`x-observed-interaction-contracts.ts`で追跡する。特定タイムラインに対象画像や作者リンクがない場合の`actionPerformed=false`を機能欠落と断定せず、独立fixtureまたは別サンプルで再観測する。

NyTweetDeck WebではComposer、プロフィール、ポスト詳細、画像viewerをhash route履歴へ接続した。入れ子の`post → media`を実Chromeで開き、Backで`media → post → 開始前hash`へ一段ずつ戻り、背後のカラムDOMと状態が維持されることを確認した。ポスト詳細とプロフィールはまだmodal表示なので、Xの非modal route表示とのgapは別契約として残す。

### インライン動画の可視範囲ライフサイクル

公開検索条件から動画候補を取得し、動画URL、ポストID、作者、本文を保存せず、同じ表示位置の中央表示・画面外・再表示を観測した。

- 中央表示ではvideo要素がDOMへ接続され、ミュート、再生中、inline、`readyState=4`、`networkState=2`、`volume=1`だった。
- `autoplay`属性、`loop`属性、native `controls`属性はいずれもfalseだが、公式player制御により動画は再生中だった。
- 画面外へ送ると元video要素はDOMから切り離され、pause、`readyState=0`、`networkState=0`、`currentTime=0`相当になった。
- 元位置へ戻ると新しいvideo要素がDOMへ生成され、再びミュート再生、`readyState=4`、`networkState=2`になった。
- player領域へhoverする前は可視controlが0件、hover後はbutton 5件・slider 2件になった。native controlsではなく公式custom UIが遅延表示される。

NyTweetDeck Webは要素を維持して`src`を解除するため、通信資源解放という意味は近いがDOMライフサイクルが異なる。またnative controlsを表示しており、公式のcustom player UIとは明確に異なる。`loop`既定ONと保存音量はNyTweetDeckの明示要件なので、公式の`loop=false`を理由に削除しない。

buttonのラベルやポスト内容は保存しない。各buttonを個別操作してpaused、muted、fullscreen、menu、sliderのどの状態が変化したかを次の観測で判定し、全controlの意味が揃うまでNyTweetDeckのnative controlsを先に削除しない。

個別クリックではmediaごとに可視button数が4〜5件へ変動した。左端（動画幅の約4%）は`paused`だけを反転し、再生／一時停止と確認できた。約72%位置のbuttonはvideo状態を変えずdialogを1件増やし、player設定系と確認できた。約75%位置ではmuteが反転した。約89%位置ではPicture-in-Pictureへ移行したが、同時に保持中videoのmuteも変わったため、副作用か参照差かを追加サンプルで分離する。fullscreenはheadless実行で成立しない可能性があり、falseだけでcontrol不在と判断しない。

controlの順序と個数はmedia能力によって変わるため、固定indexではなく、状態差・相対位置・optional能力から意味を決める。再生、一時停止、mute、進捗、音量、設定、Picture-in-Picture、fullscreenの全契約が独立して確認できるまでcustom player置換は未実装とする。

## 意味契約の採用条件

- 公式moduleの機能語出現だけで採用しない。
- 複数の独立した合成入力と境界値で同じ規則を確認する。
- 外部通信遮断、Cookieなし、Storage空を各runnerで再確認する。
- 公式moduleへ加えたinstrumentationはcapture内だけに置き、製品bundleへ含めない。
- 観測結果には入力分類、操作列、前状態、後状態、例外、DOMまたはメディア状態の数値だけを残す。
- 実ユーザーの本文、ID、URL、画像、動画、返信関係をfixtureへ複製しない。
- ReactとComposeで同じ意味契約を実行し、結果が一致して初めてNyTweetDeckへ取り込み済みとする。

## 対象別の最初の観測軸

### 動画プレイヤー

- viewport進入・離脱、自動再生、初期ミュート、再生位置保持、複数動画の排他
- ループ、音量、手動再生、シーク、再試行、詳細画面との状態移行
- HLS/MP4候補、poster、縦横比、GIF扱い、センシティブ表示

### 画像ビューア

- 開始画像、前後移動、境界循環、ピンチ、ダブルタップ、ホイール、パン
- pointer cancel、capture喪失、Esc、背景操作、向き・縦横比、複数画像

### リプライツリー

- 祖先、対象、子孫、分岐、続きを表示、親欠損、削除、非公開、折り畳み
- 並び順、枝線、インデント、対象選択、追加ページ、循環cursor

### URL正規化・リンク化

- `unwound_url`、`expanded_url`、`display_url`、`t.co`の優先順位
- メディア・記事URLの本文除去、句読点、括弧、絵文字、RTL、重複、無効scheme
- カードクリックと本文リンクのイベント伝播

### プロフィール遷移

- アバター、表示名、`@username`、本文メンション、リポスト文脈、引用作者
- ボタンやメディアとのクリック競合、同一画面遷移、戻る操作、存在しないユーザー
