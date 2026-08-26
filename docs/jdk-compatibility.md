# JDK互換性

NyTweetDeckはJava LTS 17・21・25を正式対応とし、配布JARをJava 17 bytecode（`--release 17`）で生成します。リリース成果物はTemurin 17で作成し、同じJARを21・25でも実行できます。

## CI固定行列

GitHub Actionsでは、`actions/setup-java v5`が公式対応する非推奨でない次の全ディストリビューションを、Java 17・21・25の直積で検証します。

- Eclipse Temurin
- Azul Zulu OpenJDK
- BellSoft Liberica JDK
- Microsoft Build of OpenJDK
- Amazon Corretto
- IBM Semeru Runtime Open Edition
- Oracle JDK
- Alibaba Dragonwell
- SAP SapMachine
- Oracle GraalVM
- GraalVM Community
- JetBrains Runtime
- Tencent Kona JDK

Oracle JDK 17とOracle GraalVM JDK 17は、`actions/setup-java`公式READMEのライセンス注意事項に従い`17.0.12`へ固定します。それ以外はLTSメジャー`17`・`21`・`25`を指定し、各メーカーが公開する該当系列の最新パッチを使用します。`check-latest`は無効にし、ランナーのツールキャッシュまたはsetup-javaが解決した検証済み配布物を利用します。

廃止予定のAdoptOpenJDK HotSpot/OpenJ9識別子は、それぞれTemurin/Semeruへ移行済みのため重複対象に含めません。`jdkfile`は任意ファイル入力でありメーカーではないため対象外です。

## 検証範囲

- 基準ジョブ: Temurin 17でBun検証、Maven全検証、ブラウザUI、OSV監査
- 互換ジョブ: 全メーカー×17・21・25でJava 17 bytecodeのコンパイルと全Javaテスト
- リリース: Temurin 17で非SNAPSHOT JARを生成し、ブラウザUIを再検証

対応一覧は[actions/setup-java v5の公式README](https://github.com/actions/setup-java/tree/v5#supported-distributions)、Java対応範囲は[Spring Boot 4.1.1の公式要件](https://docs.spring.io/spring-boot/system-requirements.html)を根拠とします。
