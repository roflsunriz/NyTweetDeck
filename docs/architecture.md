# アーキテクチャ

## 実行モデル

NyTweetDeckは、Spring Boot製Javaプロセスを製品のエントリーポイントにします。このプロセスはループバックアドレスだけでHTTPサーバーを公開し、Bun bundlerで生成したReact UIとローカルAPIを同一オリジンで配信します。

```text
ブラウザ ── http://127.0.0.1:18080 ── Spring Boot
   │                                      ├─ 静的React UI
   │                                      ├─ /api/v1/*
   │                                      └─ 将来のX通信アダプター
   └─ 非機密の表示設定だけをlocalStorageへ保存
```

## 責務境界

- `frontend/`: 表示、ユーザー操作、非機密なレイアウト設定を担当します。
- `src/main/java/dev/nytweetdeck/system/`: アプリケーション状態など、X通信に依存しないAPIを担当します。
- `src/main/java/dev/nytweetdeck/xapi/`: Android版Xのバージョン付きAPIプロファイル、OAuth署名、認証、通信を担当します。解析で確認できた仕様だけを実装し、推測のエンドポイントや固定トークンを置きません。
- 将来追加する永続化層: アカウント資格情報をOSの保護機構と組み合わせて暗号化し、スキーマバージョンと強制マイグレーションを持たせます。

## フロントエンドのビルド

BunのHTMLエントリーポイントをBun bundlerへ渡し、`target/classes/static`へ生成します。Mavenの`generate-resources`フェーズは固定済みlockfileで依存関係を確認した後、このビルドを実行します。生成物はGit管理しません。

## 現在成立している製品経路

1. `mvn spring-boot:run`でJavaアプリを起動する。
2. ブラウザでローカルアドレスを開く。
3. 空のデッキから種類を選んでカラムを追加する。
4. カラムを削除する。
5. 表示言語とテーマを変更する。
6. 再読み込み後もカラムと表示設定が復元される。

## 次の接続点

解析対象のAPKMからAPIホスト、GraphQL操作ID、RESTパス、標準ヘッダー、OCFログイン、OAuth 1.0a署名方式まで確認済みです。次は、秘密情報をコミットしないAPKMローカル抽出、OS資格情報保護、OCF状態機械、タイムラインレスポンス変換の順に製品経路へ接続します。
