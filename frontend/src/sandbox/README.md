# X公式Webリファレンス研究領域

この領域は、X公式WebをNyTweetDeckの独自実装の挙動リファレンスとして調査するための隔離境界です。

Git管理外の`x-reference-captures/`には、許可した公式CDNのJavaScript・CSS本文と、URL、サイズ、SHA-256、取得日時、匿名化済みの構造カウントだけを保存します。公式コード、生成CSS、アイコン、画像、文言を製品コードへコピーしません。

製品へ反映してよい成果は、次に限定します。

- 利用者が観測できる状態遷移
- APIのURL、method、必要フィールド、応答種別、エラー分岐
- 動画、画像ビューア、リプライツリー、URL正規化、プロフィール遷移の意味仕様
- 実データを含まない合成fixtureと契約テスト
- 上記仕様から独立して記述したReact、Java、Kotlin実装

取得・解析手順は`frontend/scripts/README.sandbox.md`を参照してください。

初回capture、隔離検証、対象別の観測軸は`x-reference-observation.md`へ記録します。

隔離実行ではログインセッションを継承しない新規BrowserContextを使い、CDPの要求interceptionで割り当てたloopback server以外を遮断します。合成mockの投入経路と外部通信遮断を先に自動検証し、その境界内で必要な公式moduleだけへ調査用exportまたはadapterを一時追加して実行します。変更後の公式moduleはcapture内だけに置き、製品コードやGit管理対象へ保存しません。
