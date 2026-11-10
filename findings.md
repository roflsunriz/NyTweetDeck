# 調査知見

- Androidのdisk cacheはX response原文なので、action後のflagが古い。復元columnは自動更新OFFでも初回再取得し、先頭page外の表示中postはdetail APIで再検証する。PCもSSE再接続後に同様に再照合する。
- 初回detailはconversation response内のfocal postを正本にし、含まれない場合だけ追加取得する。pagingでは直前に検証したfocalを再利用し、既知cacheを取得後の正本にしない。
- X composeは即時/予約、media INIT→APPEND→FINALIZE(+GIF STATUS)、poll card、place、paid/AI disclosureを別契約として保持する。Java mock serverでPOST bodyが止まる場合はHTTP/2でなくHTTP/1.1を明示する。
- X sessionは所有者承認によりOS user data領域へ平文保存するが、Git/log/API response/localStorageへ出さない。schema検証、atomic replace、直前backup、破損復旧、POSIX owner-only権限を維持する。
- Spring管理のJacksonは個別jarでなくBOMを同系列修正版へ上げ、JDK 17/21/25、packaged UI、Maven auditを確認する。
- Android依存はAGP/Gradle/Kotlin/compileSdkの互換順を守る。Coilが新Kotlin metadataを要求する場合はKotlinを先に更新し、Coil PRをrebaseする。
- 端末操作はserialを全adb commandへ明示し、検証後は同署名のnon-debuggable release APKへ戻して端末上APK hashを照合する。他taskの端末へ触れない。
- reply paginationの終了はcursor/entry/通信結果で判定し、空表示や一時失敗を終端にしない。明示retryまで現在一覧を保持する。
- 翻訳対象は表示本文とquote等の実contentを正規化し、UI labelやhidden metadataを送らない。X native translationだけを使い、rate limit/resetを追跡する。
- desktop動画full-screenはscreen/viewport遷移を分離し、Androidではplayer解放とsurface再利用を監査する。画面外では停止してsrcを解放し、縦長black barと復帰位置を実UIで確認する。
- X Web API定義は公式assetから起動時+6時間ごとに更新し、全必須operationが揃った場合だけatomic置換する。公式domain、size上限、queryId形式、feature Boolean、fallbackを検証し、token/Cookieをcaptureへ残さない。
- sandboxは新規BrowserContextで全外部通信とstorageを遮断し、複数captureのde-minifyと動的挙動から意味契約を作る。単語出現数だけで断定しない。
- shared settings移行はstoreが空のときだけ旧localStorageを読む。revision競合時は未保存操作を最新へ再適用し、初回から `.bak` を作り、backend再起動後にUIを自動復帰させる。
- 稼働版反映では対象process/配置/起動方法を特定し、認証・設定・HTTPS・autostartを保持してbackup後に置換する。再起動後のhash、HTTP/HTTPS、修正挙動まで確認し、build出力だけの更新で完了扱いしない。
- Windowsの手動起動は`run-nytweetdeck.vbs`/`run-nytweetdeck.cmd`が`wscript.exe`から非表示のWindows PowerShell 5.1を起動し、`run-nytweetdeck.ps1 -Background`で`javaw.exe`へ切り離す。`powershell.exe -WindowStyle Hidden`だけではWindows 11の`PseudoConsoleWindow`が可視のまま残りタスクバーへ出るため、コンソールを生成しない`wscript.exe`の`Run(command, 0, ...)`を使う。ログオン自動起動はクラッシュ再起動のため`wscript.exe ... --wait`で監督プロセスを維持する。非表示の5.1では`Cert:\CurrentUser\Root`が空に見えるため、専用CAの確認は`X509Store`を直接使う。
- 旧プロセスの停止直後はJARがまだロックされていることがある。`Stop-Process`後の終了待機と置換の短時間再試行を入れ、稼働中JARの置換失敗を防ぐ。
- Windowsの`format:check`は作業ツリーのCRLFで失敗することがある。インデックスはLFなので、`git -c core.autocrlf=false checkout-index --force --all`後に再実行して判定する。リリース時の依存監査は`bun audit`と`audit-maven.ps1`の両方を実行し、`happy-dom`は21.0.4以上でCVE-2026-33943を解消する。

- 2026-11-10: #40はユーザー訂正によりAndroidだけ、#45/#49もIssue本文の指定でAndroidだけ。他の#41〜#48はPC/Android両方。PCの画像切替制約を変更しない。
- 今回指定のPixel 10aのクラッシュは、実機R8 map IDと既存mapping.txtの一致で`LiveDeckController`→SSE response.close→gzip/TLSのmain-thread通信へ復号できた。動画形式起因とは断定しない。closeはidempotent flagを先に立て、cancel/response.closeをdaemon executorへ移す。`LivePipelineCloseUiTest`はmainからのcloseで実機回帰を検証する。
- 同じ動画IDでもtimeline/detailのmounted playerは別ownerで排他する。全画面・画質切替時は位置/ミュート/音量/再生/画質を共有する。AndroidのdetectTapGesturesでdouble tapを扱うとsingle tapが遅延するため、操作バー表示はonPressで即時に行う。
- PCの既存timeline cacheは64件/各200post、通知・トレンド・詳細の追加memory cacheは32件/5分。account/locale/sortをkeyにして即表示後に再検証する。詳細投機は200msの操作意図、1並列/5秒間隔、429は上流解除時刻または60秒まで停止。先読み通信にも15秒timeoutを使う。同一cache keyの複数columnは共有cacheの変更有無にかかわらず各自の空表示を初期化する。
- Androidは通知・トレンド初回responseを既存disk cache（24h/64件/128MiB）へ保存、詳細memoryは24件/2分。詳細投機は1並列/6回毎分、画像/動画は近傍4post・8候補・8試行毎分で同URLを2分抑止する。Coil/Media3共通cacheを使い、動画のrange warmingは512KiBまで。UIのlifecycle STARTED中だけ実行する。
- 10秒Undoは永続layoutへ削除を直ちに保存し、一時snapshotから同じID/元位置/設定を復元する。連続削除はそれぞれ期限管理し、別アカウントのruntimeを混在させない。Androidは削除column自身のruntime/scrollだけ保持し、元state全体を複製しない。
- 既存`how-to-update.md`のfindings anchorは実体がなく実行手順を失っていたため、実行・配布・反映・復旧の手順を正本へ復元した。Android導入補助スクリプトは`-Serial`必須で対象端末へ限定する。

- PC共有メニューは6項目以上の上向き固定popupだと先頭の短いpostでcolumn領域に切られる。body portal、visible viewport内の上下自動配置、最大高さとscrollで全操作を維持する。Chrome headlessの全画面検証は対象tabを`Page.bringToFront`してから行う。未選択tabではuserActivation=trueでも実Fullscreenが`not granted`で拒否されることを確認した。
- 連続削除のUndoでは元indexだけでは前方の複数カラムを逆順に復元できない。削除前の前後IDを保持し、生きている隣接カラムから挿入位置を決める。前方2カラムをどちらの順で戻しても元順になる回帰テストを残す。
- 合成動画の検証URLは通信stubを有効化するまでfixtureへ公開しない。自動timeline refreshが間に走ると実URLへ先行アクセスする競合がある。録画はactive/focusと実frame生成を確認してから終了し、固定時間待ちだけに依存しない。CIのdecode待機失敗に備えてrequest/metadata/readyStateを診断へ残す。
