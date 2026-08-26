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

## 次の接続点

解析対象のAPKMからAPIホスト、GraphQL操作ID、RESTパス、標準ヘッダー、OCFログイン、OAuth 1.0a署名方式まで確認済みです。Guest認証、OCF状態機械、端末プロファイル、暗号化Vaultも実装済みです。次は、明示承認後のAndroidクライアント資格情報準備、ログイン画面、タイムラインレスポンス変換の順に製品経路へ接続します。
