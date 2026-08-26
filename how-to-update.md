# 更新手順

## 前提

- JDK 21以上
- Maven 3.9以上
- Bun 1.4以上
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

Android版XのAPKMを更新した場合は、JADXでBase APKを再解析した後、次を実行します。

```powershell
.\scripts\extract-android-api.ps1 -JadxRoot x-apks\jadx-<version> -ExtractedRoot x-apks\extracted-<version>
```

生成されたAPKハッシュ、GraphQL件数、REST件数を旧版と比較し、製品で利用する操作IDとヘッダーを実コードの呼び出し元まで確認します。consumer key、secret、Bearer Token、Cookieなどの値は生成物や変更履歴へ含めません。

Androidクライアント資格情報の利用について利用者から明示承認を得た場合だけ、次を実行します。このスクリプトは値を表示せず、Git管理外の`.local/android-client.properties`へ利用者限定権限で保存します。

```powershell
.\scripts\prepare-android-client.ps1 -JadxRoot x-apks\jadx-<version>
```

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

ブラウザUIを変更した場合は、デスクトップ、狭幅、低高さの代表的なビューポートで、カラム追加・削除、設定変更、再読み込み後の復元を確認します。

```powershell
.\scripts\verify-ui.ps1
```

この検証はパッケージ済みJARを起動し、ヘッドレスChromeでデスクトップ・タブレット・モバイル・RTL設定画面を操作します。CIとリリースワークフローでも同じ検証を実行します。

リリース前はランチャーからJARを起動し、準備完了後にブラウザが開くこと、ZIP内のJARとローカル検証済みJARのSHA-256が一致することを確認します。

リリースワークフローは`v`を除いたタグをMavenの`revision`へ渡し、`SNAPSHOT`ではないJARを生成します。タグはSemVer形式で、mainの履歴に含まれるコミットを指す必要があります。

## ロールバック

更新が検証に失敗した場合は、失敗ログを保存したうえで更新コミットを戻します。永続データのスキーマを変更した場合は、更新前に作成したバックアップから復旧し、旧バージョンで読み込めることを確認します。
