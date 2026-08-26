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

## ロールバック

更新が検証に失敗した場合は、失敗ログを保存したうえで更新コミットを戻します。永続データのスキーマを変更した場合は、更新前に作成したバックアップから復旧し、旧バージョンで読み込めることを確認します。
