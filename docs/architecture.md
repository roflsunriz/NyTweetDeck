# アーキテクチャ

## 実行モデル

NyTweetDeckは、Spring Boot製Javaプロセスを製品のエントリーポイントにします。このプロセスはループバックアドレスだけでHTTPサーバーを公開し、Bun bundlerで生成したReact UIとローカルAPIを同一オリジンで配信します。

```text
ブラウザ ── http://127.0.0.1:18080 ── Spring Boot
   │                                      ├─ 静的React UI
   │                                      ├─ /api/v1/*
   │                                      └─ Android互換X通信アダプター
   └─ 非機密の表示設定だけをlocalStorageへ保存
```

## 責務境界

- `frontend/`: 表示、ユーザー操作、非機密なレイアウト設定を担当します。
- `src/main/java/dev/nytweetdeck/system/`: アプリケーション状態など、X通信に依存しないAPIを担当します。
- `src/main/java/dev/nytweetdeck/xapi/`: Android版Xのバージョン付きAPIプロファイル、OAuth署名、認証、通信を担当します。解析で確認できた仕様だけを実装し、推測のエンドポイントや固定トークンを置きません。
- `src/main/java/dev/nytweetdeck/account/vault/`: PBKDF2-HMAC-SHA256 600,000回とAES-256-GCMを使い、複数アカウント資格情報の暗号化、ロック、バックアップ復旧を担当します。
- Vault鍵は専用パスフレーズから導出し、パスフレーズはJavaプロセスの解除中メモリにだけ保持します。ロック時に配列を消去し、ブラウザや設定ファイルへ保存しません。

## フロントエンドのビルド

BunのHTMLエントリーポイントをBun bundlerへ渡し、`target/classes/static`へ生成します。Mavenの`generate-resources`フェーズは固定済みlockfileで依存関係を確認した後、このビルドを実行します。生成物はGit管理しません。

## 現在成立している製品経路

1. `mvn spring-boot:run`でJavaアプリを起動する。
2. ブラウザでローカルアドレスを開く。
3. 空のデッキから種類を選んでカラムを追加する。
4. カラムを削除する。
5. 表示言語とテーマを変更する。
6. 再読み込み後もカラムと表示設定が復元される。
7. 設定画面からAndroid端末プロファイルを保存する。
8. 暗号化アカウントVaultを作成、解除、ロックする。
9. 選択アカウントでホーム、通知、ユーザー、リスト、履歴、検索、トレンド、DM受信箱カラムを取得する。
10. cursorで過去ページを追加し、いいね、リポスト、履歴保存を実行する。
11. 投稿・返信・ポスト操作が成功すると、アカウント別SSEで表示中カラムを再取得する。
12. アカウント切替画面からAndroid OCFログインを開始し、動的subtaskを完了後、OAuth token/secretをブラウザへ返さずVaultへ暗号化保存する。

## 次の接続点

解析対象のAPKMからAPIホスト、GraphQL操作ID、RESTパス、標準ヘッダー、OCFログインと`open_account`、OAuth 1.0a署名方式、DM受信箱、Explore、Live Pipelineの接続仕様まで確認済みです。Guest認証、OCF状態機械とブラウザUI、端末プロファイル、暗号化Vault、GraphQL Feature Switch、タイムライン正規化、主要Mutation、ローカルSSE更新も実装済みです。残る外部X上の新規投稿イベントと実ログインの実地確認は、Androidクライアント資格情報の抽出・利用に対する明示承認後に行います。
