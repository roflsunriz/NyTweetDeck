# NyTweetDeck

NyTweetDeckは、ローカルで動作するカラム型Xクライアントです。Javaアプリがループバックアドレス上でAPIとWeb UIを配信し、ブラウザから利用する構成を目指しています。

カラム型UI、追加・削除・対象設定、編集可能なメインメニュー、複数アカウント切替、日本語と主要10言語の辞書・RTL、Web版Xに沿う表示設定、暗号化Vault、Web Cookie認証付きGraphQL/REST、ホーム・通知・ユーザー・リスト・履歴・検索・トレンド・DM受信箱、無限スクロール、投稿形式フィルター、アプリ内ユーザープロフィール、投稿・返信・引用・いいね・リポスト・履歴保存まで製品経路へ接続しています。

ログインはX公式Web画面を専用Chromeで開きます。パスワード、2FA、Passkey、CaptchaはXへ直接入力され、NyTweetDeckはログイン完了後のHttpOnlyセッションだけを受け取ります。Xのホーム画面が表示されたらChromeを開いたままNyTweetDeckの完了ボタンを押します。セッションは専用VaultパスフレーズからPBKDF2-HMAC-SHA256で鍵を導出し、AES-256-GCMで暗号化して保存します。一時Chromeプロファイルは取り込み後に削除します。

X WebのGraphQL queryIdとFeature定義は、起動後および6時間ごとにX公式Web資産から自動更新します。必須定義がすべて検証できた場合だけ一括反映し、取得や検証に失敗した場合は直前の検証済み定義を維持します。設定画面から状態確認と手動更新もできます。

## 必要な環境

- JDK 21以上
- Maven 3.9以上
- Bun 1.4以上
- Google Chrome（X公式ログイン時）

## 起動

```powershell
mvn spring-boot:run
```

起動後、ブラウザで `http://127.0.0.1:18080` を開きます。MavenはBun bundlerでフロントエンドを生成してからSpring Bootを起動します。

配布ZIPでは、Windowsは`run-nytweetdeck.cmd`、macOS/Linuxは`run-nytweetdeck.sh`を実行します。ランチャーはサーバーの準備完了を待ってからブラウザを開きます。

フロントエンドをホットリロードしながら開発する場合は、別々のターミナルで次を実行します。

```powershell
mvn spring-boot:run "-Dexec.skip=true"
```

```powershell
Set-Location .\frontend
bun run dev
```

開発画面は `http://127.0.0.1:5173` です。`/api/` へのリクエストはJavaバックエンドへ転送されます。

## 検証

```powershell
Set-Location .\frontend
bun run lint
bun run format:check
bun run type-check
bun run build
bun run test
bun audit
Set-Location ..
mvn verify
.\scripts\audit-maven.ps1
```

## セキュリティ上の境界

- サーバーは既定で `127.0.0.1` のみにバインドします。
- XのCookie、トークン、アカウント情報はブラウザの`localStorage`へ保存しません。
- X Webセッションは平文保存せず、暗号化Vaultだけへ保存します。Vaultパスフレーズも永続化しません。
- 現在`localStorage`へ保存するのは、カラム配置、表示言語、テーマなどの非機密UI設定だけです。
- ログイン用Chromeは専用の一時プロファイルとループバック限定のデバッグ接続で起動し、完了またはキャンセル時に終了します。

詳しい構成は [docs/architecture.md](docs/architecture.md) を参照してください。

## リリース

`v`で始まるSemVerタグをmainへプッシュすると、全検証と脆弱性監査後にタグ版の非SNAPSHOT JAR・ランチャー・ドキュメントをまとめたZIPとSHA-256ファイルをGitHub Releaseへ公開します。ワークフローはタグがmainの履歴を指すことを確認してから公開します。
