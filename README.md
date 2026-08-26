# NyTweetDeck

NyTweetDeckは、ローカルで動作するカラム型Xクライアントです。Javaアプリがループバックアドレス上でAPIとWeb UIを配信し、ブラウザから利用する構成を目指しています。

現在は最初の実装段階です。カラム型UI、カラムの追加・削除、表示設定の保存まで動作します。Xへのログインや実データ取得はまだ接続していません。

## 必要な環境

- JDK 21以上
- Maven 3.9以上
- Bun 1.4以上

## 起動

```powershell
mvn spring-boot:run
```

起動後、ブラウザで `http://127.0.0.1:8080` を開きます。MavenはBun bundlerでフロントエンドを生成してからSpring Bootを起動します。

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
- 将来扱うXのCookie、トークン、アカウント情報はブラウザの`localStorage`へ保存しません。
- 現在`localStorage`へ保存するのは、カラム配置、表示言語、テーマなどの非機密UI設定だけです。
- 解析用APKMと解析生成物はGit管理外です。

詳しい構成は [docs/architecture.md](docs/architecture.md) を参照してください。
