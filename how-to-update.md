# 更新手順

## 前提

- JDK 17、21、25のいずれか（配布物生成の基準はJDK 17）
- Maven 3.9以上
- Bun 1.4以上
- OpenSSL（WindowsではGit for Windows同梱版を利用可能）
- 作業前のGit状態が把握できていること

## 依存関係の更新

1. Spring Bootの公式ドキュメントで安定版と対応Javaバージョンを確認します。
2. npm公式レジストリと各公式リポジトリで、更新対象のリリース、保守状況、ライセンスを確認します。
3. `pom.xml`または`frontend/package.json`の固定バージョンを更新します。
4. フロントエンドの変更時は次を実行してlockfileを更新します。

```powershell
Set-Location .\frontend
bun install
```

5. lockfileの差分に、意図しない依存関係や配布元の変更がないことを確認します。

XのWeb資産またはAPI仕様が変わった場合も、queryId・operation metadata・Feature Switchは起動後と6時間ごとにX公式Web資産から自動更新されます。設定画面の「X Web API定義」から手動更新し、最新定義の使用状態と取得元バージョンを確認できます。全必須operationが揃わない場合は更新せず、同梱または直前の検証済み定義を維持します。

更新機構自体を変更した場合は、公式ドメイン制限、資産サイズ上限、queryId形式、必須operation完全性、Feature Boolean抽出、失敗時フォールバックをテストします。公開Bearer Token、Cookie、アカウント情報はログ、生成物、変更履歴へ含めません。

## 検証

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
```

Java互換性を変更した場合は、JDK 17で`--release 17`コンパイルと全テストを実行した後、JDK 21・25でも同じテストとJAR起動を確認します。CIのメーカー行列は`docs/jdk-compatibility.md`の固定一覧と一致させます。

ブラウザUIを変更した場合は、デスクトップ、狭幅、低高さの代表的なビューポートで、カラム追加・削除、設定変更、再読み込み後の復元を確認します。動画表示を変更した場合は、ビューポート上端〜中央への進入前は`src`が未設定で、進入時だけロード・自動再生され、離脱時は停止して`src`も解除されることを確認します。

```powershell
.\scripts\verify-ui.ps1
```

この検証はパッケージ済みJARを起動し、ヘッドレスChromeでデスクトップ・タブレット・モバイル・RTL設定画面を操作します。CIとリリースワークフローでも同じ検証を実行します。

リリース前はランチャーからJARを起動し、準備完了後にブラウザが開くこと、ZIP内のJARとローカル検証済みJARのSHA-256が一致することを確認します。

自動起動またはローカルドメインのスクリプトを変更した場合は、次も実行します。証明書検証は一時的な専用ルートCA、CA署名済みサーバー証明書、PKCS#12、高位ポートを使い、端末のhostsや信頼ストアを変更せず、ホスト名と証明書チェーンを検証します。

```powershell
.\scripts\verify-autostart.ps1
.\scripts\verify-local-domain.ps1
```

リリースワークフローは`v`を除いたタグをMavenの`revision`へ渡し、`SNAPSHOT`ではないJARを生成します。タグはSemVer形式で、mainの履歴に含まれるコミットを指す必要があります。
GitHub Releaseの本文には、`CHANGELOG.md`の同じバージョン見出しから次のバージョン見出し直前までを自動抽出します。タグを作成する前に対象バージョンの見出し、分類、変更項目を記載し、次のコマンドで抽出範囲を確認してください。

```powershell
.\scripts\verify-release-notes.ps1 -Version 1.0.0
```

配布ZIPには3OSのランチャー、自動起動登録・解除、ローカル証明書／hosts登録・解除スクリプトを必ず含めます。

## ロールバック

更新が検証に失敗した場合は、失敗ログを保存したうえで更新コミットを戻します。永続データのスキーマを変更した場合は、更新前に作成したバックアップから復旧し、旧バージョンで読み込めることを確認します。
