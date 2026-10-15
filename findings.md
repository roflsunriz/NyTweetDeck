# NyTweetDeck の実現手段と調査知見

作業着手前にこの文書を全文読む。共通方針と目的・行動義務・禁止事項は [AGENTS.md](AGENTS.md) を参照する。

## 共通方針に対する固有の実現手段

- 対象の共通ルール: 設計と実装方針のESLint・Prettier原則、検証。
- 理由: 既存フロントエンドはBiomeをlintと書式確認に使い、JavaとAndroidにも個別の検証が必要なため。
- 適用範囲: frontend/package.jsonと既存Maven・Gradle構成。
- 代替手段・検証: Biome、TypeScript、Bunテスト、mvn verify、梱包UI、Java/Android依存監査を実行する。保存方式に関する所有者の既存指示は下記の移動本文を参照し、秘密情報をGit・ログへ出さない。
- 文書構成: 実現手段・環境・過去の調査知見はこの文書を正本とする。利用者向け導入・使用法はREADME、更新の入口はhow-to-update、実測結果はverification、変更意図はCHANGELOGに残す。共通方針の品質・秘密情報保護・ユーザー指示を免除しない。
- 移行基準: 2026-10-16の既定ブランチ `e6eaafe8a8c46317f7e18919df205b27dea5dab6`。以下は出典の本文を移動したもので、過去の確認日時・適用条件・未検証事項を維持する。


<a id="agents-6"></a>

移動元: [AGENTS.md](AGENTS.md)。

## アクション済み表示の再検証

- AndroidのタイムラインディスクキャッシュはX応答の原文を保存するため、表示中のいいね・リポスト操作後もキャッシュ内のフラグは古いまま残る。再起動時に復元したカラムは自動更新OFFでも初回表示時に再取得する。新しい先頭ページに含まれない古い投稿は一覧へ残るため、表示範囲に入った投稿を`PostDetailRepository.loadFocal`で個別に再検証する。`DeckViewModelTest.revalidatesRestoredActionColorsEvenWhenAutoRefreshIsOff`参照。
- Androidの投稿詳細は既知の投稿を先に表示するが、`PostDetailRepository`でそれを取得後の正本として優先すると古いアクション色が残る。初回詳細では会話応答の対象投稿を優先し、含まれない場合だけ投稿詳細を追加取得する。ページングでは直前に確認した対象投稿を再利用する。`PostDetailRepositoryTest`の初回詳細・ページング検証参照。
- PC版でバックエンドのみ再起動してブラウザを開いたままにすると、メモリ内の投稿状態が残る。SSE再接続後は自動更新OFFでもタイムラインを再取得し、先頭ページに含まれない表示中の投稿も詳細APIで再検証する。`timeline-column.test.tsx`の接続復帰テスト参照。


<a id="agents-7"></a>

移動元: [AGENTS.md](AGENTS.md)。

## 投稿作成のX Web契約

- 2026-09-27にログイン済みX Web資産の`main.941731a8bedadd89a.js`とCompose関連チャンクで確認した。即時投稿のGraphQL `CreateTweet`では画像/GIFの`media.media_entities`、投票の`card_uri`、場所の`geo.place_id`、有料パートナーシップの`content_disclosure.advertising_disclosure.is_paid_promotion`、AI生成の`content_disclosure.ai_generated_disclosure`を送る。予約は`CreateScheduledTweet`へ`post_tweet_request`と秒単位`execute_at`を送る。`PostServiceTest`と`PostComposerRepositoryTest`参照。
- メディアは`upload.x.com/i/media/upload.json`のINIT→APPEND→FINALIZE（GIFはSTATUSまで）、投票は`caps.x.com/v2/cards/create.json`、場所検索は`api.x.com/1.1/geo/places.json`へ`query_type=tweet_compose_location`と`search_term`を送り、`places[].place`を使用する。2026-09-27にPCとPixel 10aで未投稿の画像/GIF・投票カードと現行の場所検索形式に成功した。公開投稿を伴う実X検証とは分け、変更時は署名、サイズ、組み合わせ、失敗時の入力維持を確認する。
- Windows/JDK 25のローカル`HttpServer`で投稿用POST本文を検証した際、Java `HttpClient`既定のHTTP/2交渉では本文の受信が止まり、HTTP/1.1を明示すると画像・投票の模擬サーバーテストと実X通信が成功した。`ComposerWebClientTest`と`ComposerWebClient.exchange`参照。テスト失敗を画像形式の問題と混同しない。


<a id="agents-8"></a>

移動元: [AGENTS.md](AGENTS.md)。

## 認証情報の保存
- 自動起動・自動検証を人手なしで成立させるため、X WebセッションをOS標準のユーザー別アプリケーションデータ領域へ暗号化せず保存する方式を採用する。この平文保存はプロジェクト所有者がリスクを理解したうえで明示的に許可している。保存先はインストール場所や作業ディレクトリへ依存させない。
- 保存先をloopback上で動作するNyTweetDeckプロセスの信頼境界とし、秘密情報をGit、ログ、APIレスポンス、ブラウザの`localStorage`へ含めない。
- 保存処理ではスキーマ検証、原子的置換、直前バックアップ、破損時復旧を維持し、POSIX環境では所有者だけが読み書きできる権限を設定する。


<a id="agents-9"></a>

移動元: [AGENTS.md](AGENTS.md)。

## 環境

- Spring Bootの管理下にあるJacksonは個々のjarだけを上げず、`pom.xml`の`jackson-bom.version`で整合させる。2026-10-05のOSV監査では3.1.5に7件の未撤回GHSAがあり、同系列3.1.7とMaven依存70件の監査で解消した。変更後はJava 17・21・25のテスト、梱包済みブラウザUI、`scripts/audit-maven.ps1`を確認する。
- フロントエンドのバンドルには Bun bundler を使用する
- Java LTS 17・21・25を正式対応とし、Java 17 bytecodeを生成する
- CIではactions/setup-javaが公式対応する非推奨でない全JDKディストリビューションを17・21・25で固定検証する


<a id="agents-10"></a>

移動元: [AGENTS.md](AGENTS.md)。

## Android依存更新の順序とSDK指定

- 2026-09-27時点ではAGP 9.4.1・Gradle 9.8.0・Kotlin 2.4.20・compileSdk 37の組み合わせでAndroidの単体テスト、release Lint、releaseビルドが成功した。Gradle 9.8の`Configuration.setVisible`廃止予定警告はAGP側から出るため、次回のAGP更新時に`--warning-mode all`で再確認する（`verification.md`参照）。

- Coil 3.6以降はkotlin-stdlib 2.4（メタデータ2.4）を同梱するため、Kotlin 2.4系への更新PRを先にマージしてからCoil更新PRをリベースする。逆順だと`compileDebugKotlin`がメタデータ不整合で失敗する（2026-09-23のPR #2・#4・#11で確認、`verification.md`参照）。
- Compose/Coil/OkHttpの新版はcompileSdk 37とAGP 9.1以降を要求する。SDK追随時は`android/build.gradle.kts`（AGP）・`gradle-wrapper.properties`（Gradle）・`app/build.gradle.kts`（compileSdk/targetSdk）・`.github/workflows/ci.yml`と`android-release.yml`（SDK導入とapksignerパス）を同時に上げる。
- API 37のsdkmanager正規パッケージ名は`platforms;android-37.0`（マイナー付き）であり、`platforms;android-37`では取得失敗する。正規名はローカルの`sdkmanager --list`で確認する。
- 同一ファイルの隣接行を変更するDependabot PR同士は自動リベースで競合解消できない。PRブランチをcheckoutしてrebaseし両取りに解消してpushする（非対話で`$env:GIT_EDITOR = "true"`を使う）。手動解決後はautomationのclassifyが対象外化するため手動マージする。


<a id="agents-11"></a>

移動元: [AGENTS.md](AGENTS.md)。

## Android端末の作業分離

- 返信とおすすめを分離した作業では、実機検証と反映をAQUOSに限定した。2026-09-27の投稿状態・作成作業ではユーザーがPixel 10aの接続を知らせ、当該端末で検証後に署名付き非debuggable版へ復旧・更新した。複数端末接続時はadbへ対象シリアルを必ず明示し、他タスクの端末を選ばない。


<a id="agents-12"></a>

移動元: [AGENTS.md](AGENTS.md)。

## 返信ページ終端の判定

- Xは返信終端でも新しいBottom cursorを返す。2026-09-05のAQUOS実機で、`TimelineTerminateTimeline`の`direction: Bottom`とcursorが同じ応答に含まれることを確認した。Android/Javaの`TimelineResponseParser`ではこの終了指示をcursorより優先する。空ページや表示上の返信件数だけを根拠に終了判定しない。回帰・実機検証は`verification.md`参照。

- Androidの返信内遷移は`PostCard`→`DeckViewModel.openPostDetail`→`PostDetailController`で既に接続され、返信ポストも`findLoadedPost`の対象になる。詳細の返信をたどる変更では、カードの外枠だけでなく本文への実タップとOSの戻るを検証する。`RecursiveReplyNavigationTest`が3段階の往復と返信作成・いいねの独立動作を対象にする。

- 詳細応答の`tweetdetailrelatedtweets-`モジュールは「もっと見つける」のおすすめで、返信ではない。`entryId`/`entry_id`および追加モジュールの所属IDで区別し、会話専用解析で`relatedPosts`へ分離する。返信線・品質別折り畳み・祖先探索へ混ぜず、実際の返信とIDが重複した場合は返信を優先する。


<a id="agents-13"></a>

移動元: [AGENTS.md](AGENTS.md)。

## 翻訳対象の本文

- 翻訳要求は、元本文からHTTP(S) URL・独立したメンションを除いた後にUnicode Letterが残る場合だけ行う。Androidの`TranslationCandidate`には元本文を必ず渡し、通常ポスト・返信・引用で同じガードを通す。既存の失敗状態が残っていても、本文なしの投稿には再試行表示を出さない。`TranslationTextTest`と`TranslationGuardUiTest`参照。

- ノートは`BirdwatchFetchOneNote`の同梱訳を優先し、未提供なら`https://x.com/i/api/2/grok/translation.json`へPOSTする。NOTEは`content_type: COMMUNITY_NOTE,id`と翻訳先ヘッダー、ポストは`content_type: POST,id,dst_lang`を使用する。ノートIDをPOST種別として送らない。同梱訳はsnake_case、ライブ応答の出典はcamelCaseも使うため、翻訳文へ原文のリンク位置を流用しない。
- ライブ応答はNDJSONのtextを連結し、エラー行・空本文は成功として保存しない。成功のみを共通`TranslationMemory`でメモリ保持し、同時要求を共有する。ポスト/ノート・アカウント・翻訳先を混同せず、失敗や未提供を成功キャッシュに残さない。再表示テストとAQUOSの`LiveTranslationMemoryTest`を参照。
- `is_community_note_translatable`も取得言語に依存する。原文言語が判明していれば設定した翻訳先との違いで判定し、UI言語で取得したfalseを別の翻訳先への禁止フラグとして使わない。


<a id="agents-14"></a>

移動元: [AGENTS.md](AGENTS.md)。

## 設定画面

- Android設定は`SettingsDialog.kt`へ分離し、目的別の項目と任意開閉の「詳細情報」「使い方」で構成する。選択肢は折り返し、言語はドロップダウンを使う。新たな長文説明を常時表示へ戻さない。`SettingsOrganizationUiTest`、`DeckUiTest`、`ApkUpdateUiTest`で導線を検証する。
- AQUOSの設定ドロップダウンはComposeがidleでもOSフォーカス移動が未完了のことがある。閉じる実機テストはWindowManagerの入力先がActivityからPopupへ移ったことを待ってからシステムの戻るキーを送る。`SettingsOrganizationUiTest`参照。通常画面の戻るへ無条件に展開せず、Popup上のテストに限定する。


<a id="agents-15"></a>

移動元: [AGENTS.md](AGENTS.md)。

## 画像ビューアの端

- 画像の並びは有限で、先頭・末尾をループさせない。移動先の計算だけでなくAndroidの隣接ページ描画でも範囲外を除外する。1/2/4枚、末尾から開く場合、両方向の連続操作を検証する。動画のループ再生設定とは別の仕様。


<a id="agents-16"></a>

移動元: [AGENTS.md](AGENTS.md)。

## デスクトップ動画の全画面

- PCの投稿作成画面は`document.body`へのportalで表示する。詳細・プロフィールの`backdrop-filter`内へfixed要素を入れると祖先の表示領域へ束縛され、返信画面が背面になる。Androidも`NyTweetDeckApp`の投稿Dialogを他の詳細Dialogより後にComposeする。`ReplyComposerRouteUiTest`と`issue-regressions-cdp.ts`で返信元の画面維持と実入力を検証する。
- React StrictModeのeffect再実行では、overlayのpush→cleanupで親へreplace→再pushが親履歴を重複させる。`useOverlayRoute`はcleanupで復元した同一instanceのentryを次setupでreplaceして再利用し、作成画面を閉じる際はBack完了後にUIを外す。capture段階の画像キーボード処理はlayout effectでDOM除去と同期して解除する。`use-overlay-route.test.tsx`、`image-viewer.test.tsx`、実ブラウザの履歴traceで確認する。
- Androidの新着バナーは浮動overlayで、LazyColumnのitemではない。復元キーへバナーの架空itemを混ぜない。先頭監視と新着・ヘッダボタンで同じ通知を重複解除しないよう、カラムの通知count/refreshGenerationごとの解除を共有する。`TimelineNavigationUiTest`参照。
- Androidのプロフィールと詳細の親文脈ポストはtimelines外に保持される。共通action controllerの検索・更新対象へ`userProfile.posts`と`postDetail.page.contextPosts`を含める。失敗時の30秒待機はnetwork workerと分離し、新操作を待たせず古い失敗で戻さない。`PostActionControllerTest`参照。
- 画面保持はPCのHTMLVideoElementごとの再生要求を共有Wake Lockへ集約し、hiddenで解除・visible復帰で再取得する。OS省電力による解除で即再取得を繰り返さない。Androidは各PlayerViewの`keepScreenOn`を用い、root Windowの共有フラグを個別プレイヤーで競合更新しない。2026-10-11の#17対応で確認。
- AndroidのFIT余白からCropプレビューが透けるため、接続中の動画プレイヤー全体を黒背景にする。切断時はAndroidViewを外し、`keepContentOnPlayerReset(false)`で古いフレームを破棄する。PCは最初のplayingでposterを解除し、pause・全画面切替では戻さない。`VideoPlaybackSurfaceUiTest`と動画テスト参照。
- PCの動画要素をaspect-ratioの親へ通常配置してheight:100%にすると、親が178pxでも要素が360pxになり上下がclipされる。video/posterをabsoluteで親実寸へ収め、親に高さ上限を設定する。可視枠の上下識別帯と左右黒余白を実画素で検査する。検証用blob動画はCSPで拒否されるため、CSPを変えずsame-originの検証応答へ供給する。

- Fullscreen APIでプレイヤーがトップレイヤーへ移ると、カラムをrootとしたIntersectionObserverが非交差を返す。全画面の要求中・表示中は動画を解放したり自動再生判定で停止したりしない。終了時は全画面中の古い非交差値を使わず、監視を再設定してカラム内の状態を測り直す。`configured-video-fullscreen.test.tsx`参照。


<a id="agents-17"></a>

移動元: [AGENTS.md](AGENTS.md)。

## Android動画の全画面とメモリ

- 全画面表示中はインライン側のExoPlayerを解放し、同時デコードを全画面の1本だけにする。高ビットレート動画で二重デコードになるとPixel 10aの256MBヒープで`MediaCodec.getOutputBuffer`のOOMが起き、再起動後の自動再生で繰り返し落ちる。`InlineVideoPlayer`の`suspended`→`MediaPreview`/`MediaTile`の`videosSuspended`（`PostCard`/`QuoteCard`では`selectedMedia != null`）で画面外と同じ解放経路を使い、閉じれば再生成する。`MediaViewerUiTest`の`suspended`検証参照。
- Pixel 10aは別タスク用で原則操作しないが、全画面OOM修正と共有翻訳版の反映（2026-09-06）に限りユーザーが操作・更新を許可した。adbはシリアル（`63131JEA301496`）を必ず明示する。debug/releaseは同一署名（安定debugキーストア）のため`install -r`でデータ保持のまま上書きでき、releaseは`NYTD_ANDROID_KEYSTORE`等を渡した`assembleRelease`で署名済み`app-release.apk`を作る。検証後は非debuggable releaseへ戻し、端末APKのハッシュをビルド物と照合する。
- 実機Composeテストの注意：ボタンの内容説明は結合後ツリーで参照する（非結合ツリーのボタン単体には説明が載らない）。`autoAdvance = false`時はクリック後の再描画へ`advanceTimeBy`を足す。`safeMediaUri`がtwimg限定のためfixture動画では実デコード検証ができず、実動画は`LiveVideoPlaybackSmokeTest`（読み取り専用）で行う。


<a id="agents-18"></a>

移動元: [AGENTS.md](AGENTS.md)。

## X Web API定義の更新

- `https://x.com/home`は未ログインだと307でログインページへ飛ぶため、`XWebMetadataResolver`は保存済みWebセッション（`AccountStore.firstWebSession`のCookie/CSRF）で取得する。匿名取得に戻さない。セッション無効時はログインし直しを求める。
- ログイン済みHTMLは`responsive-web/client-web/`の`vendor`・`i18n`・`main.<hash>.js`を参照する。チャンクはマニフェストの`名前`＋`.`＋`ハッシュ`＋`a.js`（`.js`は404）で取得する。`RELEVANT_CHUNK_MARKERS`には`list`を含める。
- リスト系5操作（`ListLatestTweetsTimeline`・`CombinedLists`・`ListsDiscovery`・`ListAddMember`・`ListRemoveMember`）はホーム画面のチャンク図に現れないため、更新時は直前の検証済み定義を維持する（`XApiProfileService.applyResolved`の保持動作）。見つからないことを理由に更新全体を失敗させない。
- 同梱の`web-current.json`・`web-boolean-feature-defaults.json`は失効する。タイムラインが422/502になったら現行資産から取り直す。手順は`verification.md`の復旧記録参照。`versionName`は取得元`main.<hash>.js`に合わせる。
- ログアウト画面（`https://x.com/`）は新系列`x-web/x-web/entry-client-logged-out-*.js`を使うが、ログイン後のGraphQL定義は旧系列`responsive-web/client-web`に残っている。系列を混同しない。
- 通知タイムラインは変数`timeline_type`が必須で、既定値は`All`（他に`Mentions`・`Priority`・`Verified`・`SuperFollowers`）。422時は`GRAPHQL_VALIDATION_FAILED`の変数名と`bundle.Notifications`の`fetchNotifications`で現行仕様を確認する（`TimelineQueryFactory`）。Android版も同じ変数を送る。
- Android版の同梱定義はデスクトップ版と`src/main/resources/x-api/`を共有する（`android/app/build.gradle.kts`のassets参照）。デスクトップ側の更新だけでAndroidの同梱値も更新される。
- Androidの定義更新も保存済みセッション（`XApiEnvironment`の`webSessionProvider`→`XWebMetadataResolver`）で取得し、欠落操作は維持する。`AssetFetcher`はヘッダー付きで取得し、Cookieはホーム取得にだけ付ける。
- 署名（`X-Client-Transaction-Id`）素材の取得元ホーム画面もログイン必須のため、両面の署名サービスは保存済みセッションで取得する。Androidでは署名が全GraphQLに付くため、署名生成の失敗はログイン検証を含む全面停止になる。デスクトップはミューテーションのみに付ける。
- Androidのログイン検証時点ではアカウント未保存のため、保存先だけを見ると署名生成が失敗する。`PendingWebSessionProvider`で検証中のセッションを一時的に差し出し、終了後に必ず保存済みへ戻す。


<a id="readme-8"></a>

移動元: [README.md](README.md)。

## ソースから起動する

開発環境には次が必要です。

- JDK 17、21、25のいずれか（Java 17 bytecodeを生成）
- Maven 3.9以上
- Bun 1.4以上
- Google Chrome

バックエンドとホットリロード対応フロントエンドをまとめて起動するには、リポジトリ直下で次を実行します。両方の準備完了後に開発画面がブラウザで開き、終了時は`Ctrl+C`で両プロセスをまとめて停止できます。Windowsのログは画面に表示される`target/dev-*.log`、macOS/Linuxのログは同じターミナルで確認できます。

```powershell
.\scripts\run-dev.cmd
```

macOSまたはLinuxでは次を実行します。

```sh
sh ./scripts/run-dev.sh
```

この開発用スクリプトはJARやバージョン付きファイル名を参照せず、現在のソースコードをMavenとBunから直接起動します。既定ではバックエンドに18080番、フロントエンドに5173番を使用します。別ポートで起動する場合、Windowsでは`.\scripts\run-dev.ps1 -BackendPort 18082 -FrontendPort 5174`、macOS/Linuxでは`sh ./scripts/run-dev.sh --backend-port 18082 --frontend-port 5174`を実行します。

開発画面は <http://127.0.0.1:5173> です。`/api/`へのリクエストはJavaバックエンドへ転送されます。

個別に起動する必要がある場合は、1つ目のターミナルで`mvn spring-boot:run "-Dexec.skip=true"`、2つ目のターミナルで`frontend`へ移動して`bun run dev`を実行できます。


<a id="readme-9"></a>

移動元: [README.md](README.md)。

## 検証する

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
.\scripts\audit-gradle.ps1
```

更新作業と復旧方法は [how-to-update.md](how-to-update.md)、構成は [docs/architecture.md](docs/architecture.md)、JDKメーカー別のCI対象は [docs/jdk-compatibility.md](docs/jdk-compatibility.md) を参照してください。

### README画像を再生成する

WindowsとGoogle Chromeで次を実行すると、`docs/images`のカバー、カラム、画像ビューア、設定画面を同じ固定フィクスチャから再生成できます。隔離した一時設定とモックアカウントだけを使用し、保存済みXセッションや実ポストへはアクセスしません。

```powershell
.\scripts\capture-readme.ps1
```

すでに現在のソースからJARを生成済みの場合は、`-SkipBuild`を付けてキャプチャだけを再実行できます。


<a id="readme-10"></a>

移動元: [README.md](README.md)。

## リリース

`v`で始まるSemVerタグをmainへプッシュすると、検証と脆弱性監査の成功後に、JAR、各OS用ランチャー、ドキュメント、SHA-256ファイルをまとめたZIPをGitHub Releaseへ公開します。本文には`CHANGELOG.md`の該当バージョンだけを使用し、ワークフローはタグがmainの履歴を指すことを確認してから公開します。

`android-v`で始まるSemVerタグをmainへプッシュすると、Androidの単体テスト、Lint、依存関係監査、署名検証の成功後に、バージョン付きAPKとSHA-256ファイルを別のGitHub Releaseへ公開します。本文には`CHANGELOG.md`の`Android <version>`節だけを使用します。


<a id="readme-11"></a>

移動元: [README.md](README.md)。

## 依存更新の自動処理

Dependabot は対象の依存関係を毎週確認します。patch／minor／major 更新は PR のチェック（CI）が成功した後に自動で squash merge されます。CI の失敗ジョブは 1 回だけ再実行します。再失敗した PR は残して手動で修正します。


<a id="update-1"></a>

移動元: [how-to-update.md](how-to-update.md)。

## 前提

- JDK 17、21、25のいずれか（配布物生成の基準はJDK 17）
- Maven 3.9以上
- Bun 1.4以上
- OpenSSL（WindowsではGit for Windows同梱版を利用可能）
- Android版の検証時はAndroid SDK 37.0（`platforms;android-37.0`）・build-tools 37.0.0とJDK 17
- 作業前のGit状態が把握できていること


<a id="update-2"></a>

移動元: [how-to-update.md](how-to-update.md)。

## 依存関係の更新

Spring Bootの推奨Jacksonに監査失敗がある場合は、公式の脆弱性情報とMaven Centralの公開版を確認し、`pom.xml`の`jackson-bom.version`で同系列の修正版へまとめて更新します。coreとdatabindだけを別々に指定せず、解決後の依存一覧と`audit-maven.ps1`の結果を確認します。更新が不要になった場合も、BOM指定を外した状態で同じテストと監査を通してから変更します。

1. Spring Bootの公式ドキュメントで安定版と対応Javaバージョンを確認します。
2. npm公式レジストリと各公式リポジトリで、更新対象のリリース、保守状況、ライセンスを確認します。
3. `pom.xml`または`frontend/package.json`の固定バージョンを更新します。
4. フロントエンドの変更時は次を実行してlockfileを更新します。

```powershell
Set-Location .\frontend
bun install
```

5. lockfileの差分に、意図しない依存関係や配布元の変更がないことを確認します。

直接依存関係を一括で最新版へ更新する場合は、先に`bun outdated`で対象を確認し、`frontend`で`bun update --latest`を実行します。`frontend/biome.json`のschema URLもBiome本体と同じ版へ合わせます。AndroidはGoogle MavenとMaven Centralの公式メタデータで安定版を確認し、AGP・Gradle・Kotlin・compileSdkの互換性を保って更新します。更新後は両版の全テストと監査を実行し、失敗した場合は変更差分から原因を特定してから公開します。

XのWeb資産またはAPI仕様が変わった場合も、queryId・operation metadata・Feature Switchは起動後と6時間ごとにX公式Web資産から自動更新されます。設定画面の「X Web API定義」から手動更新し、最新定義の使用状態と取得元バージョンを確認できます。全必須operationが揃わない場合は更新せず、同梱または直前の検証済み定義を維持します。

更新機構自体を変更した場合は、公式ドメイン制限、資産サイズ上限、queryId形式、必須operation完全性、Feature Boolean抽出、失敗時フォールバックをテストします。公開Bearer Token、Cookie、アカウント情報はログ、生成物、変更履歴へ含めません。

X公式Webの動画、画像、返信、リンク、プロフィール遷移の挙動が変わった場合は、`frontend/scripts/README.sandbox.md`を確認し、raw CDPを公開する専用Chromeで公式資産を再取得します。captureはGit管理外の`frontend/src/sandbox/x-reference-captures/`だけへ保存し、Cookie、Authorization、header、HTML、DOM、スクリーンショット、実ユーザーデータを保存しません。

投稿作成の仕様が変わった場合は、X公式のCompose関連資産とログイン済みWebセッションの読み取り操作で、`CreateTweet`・`CreateScheduledTweet`、メディアアップロード、投票カード、場所検索、開示フラグの送信形を再確認します。変更後は`ComposerWebClientTest`・`PostServiceTest`・`PostComposerRepositoryTest`と両版の投稿画面テストを実行します。画像/GIFアップロードと未公開の投票カード・場所検索は公開投稿を作らず検証できます。実際の公開投稿や予約投稿を行う検証は、その対象アカウントと後片付けを確定してから実行します。

```powershell
Set-Location .\frontend
bun run sandbox:capture-x-reference
bun run sandbox:analyze-x-reference
bun run sandbox:inventory-x-reference
bun run sandbox:verify-x-offline
bun run sandbox:run-x-url-contract
```

意味契約を更新する前に、新規BrowserContextで外部HTTP・HTTPS・WebSocket・FTPが遮断され、CookieとWeb Storageが空であることを確認します。公式moduleの対象関数は複数captureのde-minify結果と動的挙動から特定し、調査用instrumentationはcapture内だけへ追加します。機能語の出現数だけで挙動を断定せず、合成mockの境界値と操作列で前後状態を観測してから、Xコードを含まない契約テストと独自実装へ反映します。

観測範囲は動画、画像、返信、リンク、プロフィール遷移へ限定しません。`sandbox:inventory-x-reference`で全surface・コンテンツ型・UI要素・状態・入力・環境・データ条件の台帳と、実captureから自動発見したbundle token・role・タグ・ARIA状態を比較します。新しい要素が見つかった場合は既存の対象集合へ追加し、未観測、観測済み、契約化済み、デスクトップ実装済み、Android実装済み、両版検証済みを区別して追跡します。

X公式挙動を取り込む際も、自由追加・削除・並べ替え可能な任意数カラム、左メインメニューの常設とカスタマイズ、複数アカウントのログイン状態保持・自動選択・切替を製品不変条件として維持します。Xの単一フィードまたは単一アカウント前提の状態はカラム単位・選択アカウント単位へ適応し、これらを削除する変更を互換対応として扱いません。

Issue #1のようにデスクトップ版とAndroid版の両方へ機能を追加する場合は、タイムライン取得・設定移行・詳細返信・動画再生の各経路を同じ受入条件で更新します。カラムの「トップ／最新」、タイムライン自動更新、投稿の翻訳先言語、動画画質は両版の設定へ追加し、旧設定を既定値で移行できることを確認します。返信詳細は親返信の文脈と追加ページのcursorを、動画はX応答の複数MP4 variantを、各版の単体テストとUIテストで確認します。


<a id="update-3"></a>

移動元: [how-to-update.md](how-to-update.md)。

## 検証

動画と投稿操作を変更した場合は、Androidの`VideoPlaybackSurfaceUiTest`、`PostCardInteractionUiTest`、`ReplyComposerRouteUiTest`、`TimelineNavigationUiTest`を接続端末で実行します。動画の再生・停止・全画面・画面外での画面保持、縦長動画の黒余白、詳細・プロフィール・再帰返信からの投稿画面、横スワイプロック中のカラム選択を確認します。PC版の`verify-ui.ps1`でも同じ投稿導線、250件の新着移動、動画の余白と画面保持を検証します。投稿と反応は合成応答で確認し、X上の公開投稿や実際の反応は変更しません。

複数チャットが同じ実機を使う場合は、操作開始前に使用順を調整し、すべてのadb操作にシリアルを明示します。検証後は同一署名の非debuggable release APKへ戻し、端末上のAPKとビルド物のハッシュを照合してから端末の解放を通知します。

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
```

開発ランチャーを変更した場合は、リリースJARやバージョン付きファイル名を用意せず、別ポートでバックエンドとフロントエンドの準備完了および終了後のポート解放を確認します。

```powershell
.\scripts\run-dev.ps1 -BackendPort 18082 -FrontendPort 5174 -NoBrowser -ExitAfterReady
```

```sh
sh ./scripts/run-dev.sh --backend-port 18083 --frontend-port 5175 --no-browser --exit-after-ready
```

Java互換性を変更した場合は、JDK 17で`--release 17`コンパイルと全テストを実行した後、JDK 21・25でも同じテストとJAR起動を確認します。CIのメーカー行列は`docs/jdk-compatibility.md`の固定一覧と一致させます。

ブラウザUIを変更した場合は、デスクトップ、狭幅、低高さの代表的なビューポートで、カラム追加・削除、設定変更、再読み込み後の復元を確認します。動画表示を変更した場合は、ビューポート上端〜中央への進入前は`src`が未設定で、進入時だけロード・自動再生され、離脱時は停止して`src`も解除されることを確認します。

設定永続化を変更した場合は、共有ストアが空のときだけ旧`localStorage`を移行すること、既存共有設定をアドレス別の旧値で上書きしないこと、設定版1〜7のカラムを保持して現行版へ原子的に移行できること、`127.0.0.1`と`localhost`から同じリビジョンを取得できること、同時更新の競合時に未保存操作を最新リビジョンへ再適用することを確認します。復旧検証では、初回保存時から`.bak`が作成されること、本体の破損時と欠落時に正常な`.bak`を復元すること、バックアップがない場合と両方が破損した場合に既定設定を再生成して再起動後も読めること、バックエンド停止中に読込エラーになった画面が再起動後に自動復帰することを確認します。

```powershell
.\scripts\verify-ui.ps1
```

この検証はパッケージ済みJARを起動し、ヘッドレスChromeでデスクトップ・タブレット・モバイル・RTL設定画面を操作します。CIとリリースワークフローでも同じ検証を実行します。

リリース前はランチャーからJARを起動し、準備完了後にブラウザが開くこと、ZIP内のJARとローカル検証済みJARのSHA-256が一致することを確認します。

自動起動またはローカルドメインのスクリプトを変更した場合は、次も実行します。証明書検証は一時的な専用ルートCA、CA署名済みサーバー証明書、PKCS#12、高位ポートを使い、端末のhostsや信頼ストアを変更せず、ホスト名と証明書チェーンを検証します。

```powershell
.\scripts\verify-autostart.ps1
.\scripts\verify-installation.ps1
.\scripts\verify-local-domain.ps1
```

リリースワークフローは`v`を除いたタグをMavenの`revision`へ渡し、`SNAPSHOT`ではないJARを生成します。タグはSemVer形式で、mainの履歴に含まれるコミットを指す必要があります。
GitHub Releaseの本文には、`CHANGELOG.md`の同じバージョン見出しから次のバージョン見出し直前までを自動抽出します。タグを作成する前に対象バージョンの見出し、分類、変更項目を記載し、次のコマンドで抽出範囲を確認してください。

```powershell
.\scripts\verify-release-notes.ps1 -Version 1.0.0
```

配布ZIPには3OSのランチャー、自動起動登録・解除、ローカル証明書／hosts登録・解除、統合インストール／解除、Windows共通ランタイムスクリプトを必ず含めます。

Android版は`android/app/build.gradle.kts`の`versionName`と一致する`android-v<version>`タグをmainのコミットへ付けます。`CHANGELOG.md`へ`## [Android <version>]`節を確定し、次のコマンドでAndroid専用リリース本文を確認します。

```powershell
.\scripts\verify-release-notes.ps1 -Version 0.2.0 -SectionPrefix Android
```

タグのpush後はAndroid Releaseワークフローが単体テスト、release Lint、Gradle依存関係のOSV監査、署名付きAPK生成、`apksigner`検証、SHA-256生成を行います。公開されたAPK名のバージョン、署名検証、SHA-256、CHANGELOG本文がタグと一致することを確認します。

更新ボタンの変更時は、設定表示直後の確認中、同一版、旧版、新版、通信失敗後の再試行、連打、設定の再表示を確認します。デスクトップの現在版はMavenが生成するbuild-infoの`revision`、Androidは`BuildConfig.VERSION_NAME`を使用します。デスクトップのSNAPSHOTは基底版で比較し、版が不明なビルドではダウンロードを開始しません。


<a id="update-4"></a>

移動元: [how-to-update.md](how-to-update.md)。

## ロールバック

返信の取得処理を変更した場合は、`verification.md`の「返信詳細の終端」に従って、返信0件・複数返信の終端と通信失敗後の明示再試行を検証します。Androidの実機検証後は同じ署名の修正済み非debuggable release APKへ戻し、実機から取得したAPKのハッシュをビルド済みAPKと比較します。Windowsは稼働JARを退避して修正済みJARへ置換し、既存の自動起動タスクから起動したプロセス、JARのハッシュ、HTTP/HTTPSのready応答を確認します。認証・設定・証明書は変更しません。

統合インストーラーはJARとランチャーを置換前に`.bak`へ退避し、更新後のHTTP/HTTPS確認に失敗した場合は旧ファイルと旧自動起動タスクを復旧して再起動します。更新がローカル検証またはCIで失敗した場合は、失敗ログを保存したうえで更新コミットを戻します。永続データのスキーマを変更した場合は、更新前に作成したバックアップから復旧し、旧バージョンで読み込めることを確認します。共有設定は本体の欠落・破損時に`settings.json.bak`から自動復旧し、両方とも利用不能な場合は既定設定へ再初期化します。初回移行が失敗した場合は旧`localStorage`を削除せず再試行可能な状態を維持します。


<a id="update-5"></a>

移動元: [how-to-update.md](how-to-update.md)。

## Dependabot PR の更新

前提は `.github/dependabot.yml` と PR 用 CI（CI）です。更新 PR の head SHA と `gh pr checks <PR番号>` の結果を確認してください。patch／minor／major は全チェック成功後に自動取り込みされます。初回 CI 失敗は failed jobs のみを 1 回再実行し、再失敗した PR は残して手動で修正します。

設定を変えたときは `actionlint .github/workflows/dependabot-automation.yml` と実際の PR の Actions 結果を確認します。問題があれば呼び出し先の共通 workflow SHA を直前の検証済み値へ戻すコミットを push します。取り込まれた依存更新に問題があれば通常の revert コミットで復旧します。

CI 完了より Dependabot の分類が遅れる場合は、`callback_workflow_file` が指す呼び出し側 workflow を `workflow_dispatch` し、同じ PR 番号・head SHA・全チェックを再確認する。呼び出し側のファイル名を変える際はこの入力も一緒に更新する。
