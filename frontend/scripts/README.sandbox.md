# X公式Webリファレンス観測sandbox

`scripts/sandbox/`は、X公式Webの公開配信資産と利用者が観測できる構造を、製品コードから隔離して調査する開発者向け領域です。外部コードをNyTweetDeckへimportせず、確認した意味契約だけを独自の型・テスト・実装へ移します。

## 安全境界

- raw CDPを公開する専用Chromeの新規一時タブだけを使用し、既存タブを遷移させない。
- 保存する公式コードは`abs.twimg.com`の許可済みパスにあるJavaScriptとCSS本文だけとする。
- Cookie、Authorization、全リクエスト・レスポンスヘッダー、HTML、DOM、スクリーンショット、ポスト本文、ユーザー名、ユーザーIDを保存しない。
- URLのqueryとfragmentは保存前に除去する。
- captureは`src/sandbox/x-reference-captures/`へ隔離し、Git、製品bundle、配布物へ含めない。
- de-minifyはメモリー上だけで行い、解析結果にはファイル別の意味シグナル件数だけを残す。
- X上の状態を変える投稿、返信、いいね、リポスト、フォロー等は、このsandboxから実行しない。

## 実行

`http://127.0.0.1:9222`でraw CDPを公開する専用Chromeを起動し、その専用プロファイルでXへログイン済みであることを確認してから、`frontend/`で実行します。

```powershell
bun run sandbox:capture-x-reference
bun run sandbox:analyze-x-reference
bun run sandbox:inventory-x-reference
bun run sandbox:verify-x-offline
bun run sandbox:run-x-url-contract
```

別endpointまたはX公式ページを観測する場合は引数を渡します。hostは`x.com`に限定されます。

```powershell
bun scripts/sandbox/capture-x-reference.ts `
  --cdp=http://127.0.0.1:9222 `
  --url=https://x.com/home `
  --settle-ms=12000
```

初回captureはログイン状態、ポストカード数、動画・画像数、操作ボタン数、プロフィール遷移候補数だけを匿名の数値として記録します。個別挙動の観測は、対象シナリオごとに同じ保存境界を維持した専用スクリプトを追加します。

`sandbox:verify-x-offline`は、ログインセッションを継承しない新規BrowserContextを作成し、割り当てた`127.0.0.1`の一時server以外をCDPで遮断します。外部HTTP・HTTPS・WebSocket・FTPが失敗すること、CookieとWeb Storageが空であること、合成タイムラインmockをloopbackから投入・描画できることを確認します。公式moduleを実行する個別runnerは、この検証済み境界の中で対象moduleと意味契約ごとに追加します。

`sandbox:run-x-url-contract`は、最新captureの公式`main`資産からURL entity補完関数をメモリー上で抽出し、外部通信を全面遮断した新規BrowserContextで5種類の合成entityを実行します。公式関数本体は保存せず、欠落時のfallbackと診断件数だけを意味契約としてcapture内の実行結果へ記録します。

`sandbox:inventory-x-reference`は、既知の5領域に限定せず、全surface・コンテンツ型・要素・状態・入力・環境・データ条件の手動カタログ件数と、captureで実際に見つかったbundle token、固定role、タグ、安全な既知`data-testid`、ARIA状態を集計します。自動発見結果をカタログの上限にせず、未知のbundle tokenや構造を次の観測対象へ追加します。
