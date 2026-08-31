# 検証手順

NyTweetDeckの変更は、リポジトリ直下から次の順で検証します。詳細な更新・復旧手順は`how-to-update.md`を参照してください。

```powershell
Set-Location .\frontend
bun run lint
bun run format
bun run type-check
bun run build
bun run test
bun audit
Set-Location ..
mvn verify
.\scripts\audit-maven.ps1
.\scripts\audit-gradle.ps1
.\scripts\verify-ui.ps1
.\scripts\capture-readme.ps1 -SkipBuild
```

Android版は`android`ディレクトリで`gradlew test lintDebug lintRelease assembleDebugAndroidTest assembleRelease`を実行する。認証済みAQUOSでは`run-aquos-live-tests.ps1`を使い、本体APKへtest-onlyオプションを付けず、読み取り検証後に同一署名の非debuggable release版へ戻ることを確認する。可逆mutationテストは所有者が明示的に許可した場合だけ実行し、X上の状態と一時データを原状復帰する。

## X公式Webリファレンスsandbox

公式資産captureのURL許可境界、query・fragment除去、保存名・SHA-256、loopback限定通信を単体テストで確認します。raw CDP付き専用Chromeが起動中の場合はオフラインsandboxも実行し、外部HTTP・HTTPS・WebSocket・FTP遮断、Cookie・Storage空、合成mock投入を確認します。

```powershell
Set-Location .\frontend
bun test scripts/sandbox/x-reference-policy.test.ts scripts/sandbox/offline-sandbox-policy.test.ts scripts/sandbox/x-reference-coverage-catalog.test.ts
bun run sandbox:inventory-x-reference
bun run sandbox:verify-x-offline
bun run sandbox:run-x-url-contract
bun run sandbox:capture-x-surfaces
bun run sandbox:inventory-x-surfaces
bun run sandbox:observe-x-dynamic-matrix
```

ログイン済みcaptureはXへの読み取りアクセスを伴うため、通常のCIと無認証テストでは実行しません。手動観測でも投稿、返信、いいね、リポスト、フォロー等の変更操作を実行せず、capture内にCookie、認証header、HTML、DOM、スクリーンショット、実ポスト本文、ユーザー識別子がないことをmanifestと保存ファイル種別から確認します。既知の代表例だけで全要素対応と判定せず、手動カタログと自動発見inventoryの未分類・未観測・未契約・片方だけ実装・未検証件数を確認します。

意味契約の反映後は、X互換部分だけでなく、任意数のカラム追加・削除・並べ替え・再起動復元、左メニューの常設・追加・削除・並べ替え、複数保存アカウントの前回選択・#1 fallback・切替後のアカウント別カラムを回帰確認します。いずれかを単一フィードまたは単一アカウントへ縮小する変更は不合格です。

## UIの回帰確認

- メインメニューと第1カラムの間隔を実測し、第1・第2カラム間と同じ幅であることをデスクトップとタブレットで確認します。モバイルを含め、横スクロール後もメニュー側の間隔が消えないことを確認します。
- フォロー通知を選択すると通知内の全ユーザーが名前と@ユーザー名付きで一覧表示され、識別済みユーザーをプロフィール導線として操作できることを確認します。
- いいね・リポスト・履歴保存を通信中に連続操作してもボタンが無効にならず、画面は最後の操作を即時表示し、必要なMutationだけが順序どおり送られることを確認します。最終Mutation失敗時だけ確定状態へ戻り、途中の失敗が後続意図で相殺される場合は不要なエラーを表示しないことも確認します。
- フォロー・ミュート・ブロックは押下直後に個別の完了状態となり、別操作を同時に開始できることを確認します。リスト追加・削除の連続操作は最新の表示を保ちながら送信順を維持し、失敗した最新操作だけを戻すことを確認します。
- 設定版1〜7を読み込んでも保存済みカラムが残り、現行版へ原子的に書き換わることを確認します。別画面が先に保存した競合と、保存成功後の応答だけが失われた競合でも、ローカル操作を消失・二重適用しないことを確認します。
- フルサイズ画像を100%未満まで縮小でき、左ボタンを押したまま動かした間だけ画像が移動することを確認します。ボタンを離した移動、ポインターキャンセル、キャプチャ喪失後の移動では表示位置が変わらないことも確認します。
- 複数画像の詳細で左境界へのドラッグが次、右境界へのドラッグが前の画像へ切り替わり、前後ボタン、左右キー、ズーム、リセット、Esc復帰と競合しても正しい画像と倍率へ収束することを確認します。
- A直下のB/C/D、B配下のE、E配下のFを含む返信詳細でB-E-Fが縦線・枝線・インデントとして表示されることを確認します。並び替え、スパム折り畳み、ページ追加、親欠損、自己参照、循環、過深度、LTR/RTL、狭幅でも全返信が有限時間で操作可能なことを確認します。
- 投稿を追加読込したタイムラインの下部から上へ戻り、遅延コンテンツの高さが変化しても読んでいる投稿が飛ばないことを確認します。新着投稿の差し込み後も同じ投稿の画面内位置を維持し、新規投稿通知から先頭へ移動できることを確認します。
- AQUOSではadaptive iconに黒い正方形が残らないこと、自動再生ON/OFFのインライン動画と詳細の手動再生・ループ・ミュートが動作すること、同じ動画の再表示でディスクキャッシュが利用されることを確認します。返信カーソル循環が停止し、新規投稿通知が押すまで保持され、リスト候補が専用ダイアログへ即時復元されることも確認します。
- ヘッドレスChrome検証でデスクトップ、タブレット、モバイル、RTLの各表示に横方向のページ溢れがなく、ブラウザコンソールに未処理エラーがないことを確認します。

## 失敗時の対策

- フロントエンド検証が失敗した場合は、最初に失敗したテスト名と期待値、対象DOMの実測値を確認し、テストを弱めず製品経路を修正します。
- ブラウザ検証が失敗した場合は`target/ui-server-*.log`、`target/ui-server-error-*.log`と生成されたスクリーンショットを確認します。
- ブラウザ検証は既存NyTweetDeckと競合しない空きHTTP/CDPポートと一時設定領域を使用します。明示指定したポートが使用中なら起動前に失敗し、既存プロセスへ接続しないことを確認します。
- 更新後の起動確認に失敗した場合は、統合インストーラーが作成する直前バックアップへ戻し、HTTPとHTTPSの両方が応答する旧版を再起動します。
