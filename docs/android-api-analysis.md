# Android版X API解析記録

## 対象と再現性

解析対象は `com.twitter.android` 12.19.1-release.0、versionCode `312191000`、minSdk 28です。APKMとBase APKのハッシュ、JADX処理結果は [android-apk-metadata.json](generated/android-apk-metadata.json) に固定しています。

解析にはJADX 1.5.6を使用しました。106,783クラス中106,782クラスを通常出力し、全体で1,005件の逆コンパイル警告・エラーが報告されました。エラーを無視して完全解析とみなさず、該当クラスが製品経路へ関係する場合は単一クラスのfallback出力またはsmaliで確認します。

再抽出コマンドは次のとおりです。解析対象とJADX出力はGit管理外です。

```powershell
.\scripts\extract-android-api.ps1
```

## 確認済みの通信先

- REST API: `https://api.twitter.com`
- GraphQL API: `https://api.x.com/graphql/{operationId}/{operationName}`
- メディアアップロード: `https://upload.twitter.com`

GraphQLの操作キー、operation ID、公開操作名、Query/Mutation種別は、アプリ内の`GraphQlOperationRegistry`から319件を抽出しています。全件は [android-graphql-operations.json](generated/android-graphql-operations.json) に記録しています。

`feature_switch_manifest`から値がbooleanの1,619件だけを抽出し、[android-boolean-feature-defaults.json](generated/android-boolean-feature-defaults.json) に記録しています。文字列・URL・SDKキーなどは製品リソースへ含めません。主要タイムライン操作が参照する28キーだけをGraphQL要求の`features`へ送ります。

Javaソース中で直接確認できたRESTパス145件は [android-rest-endpoints.json](generated/android-rest-endpoints.json) に記録しています。動的に組み立てられるパスはこの一覧だけでは網羅できないため、利用する機能ごとに呼び出し元も確認します。

## 標準ヘッダー

`com.twitter.network.h1`から、少なくとも次を確認しました。

- `User-Agent`: Android端末・OS・アプリ版を含む動的値
- `X-Client-UUID`: アプリ単位UUID
- `X-Twitter-Client: TwitterAndroid`
- `X-Twitter-Client-Version: 12.19.1-release.0`
- `X-Twitter-API-Version: 5`
- `Accept-Language`
- `X-Twitter-Client-Language`
- `X-Twitter-Client-DeviceID`
- `X-Twitter-Active-User`
- `X-Attest-Token`: App Attestationが利用可能な場合
- `Trusted-Device-ID`
- `X-Twitter-Client-Flavor`
- `Accept: application/json`
- `X-B3-TraceId`
- `OS-Security-Patch-Level`

認証情報やApp AttestationトークンはプロファイルJSONへ固定しません。

## 認証フロー

確認済みの段階は次のとおりです。

1. `POST /oauth2/token`へBasic認証と`grant_type=client_credentials`を送り、アプリBearer Tokenを取得する。
2. `POST /1.1/guest/activate.json`へBearer Tokenを送り、Guest Tokenを取得する。
3. `POST /1.1/onboarding/task.json`へ`flow_name=login`と`api_version=1`を送り、OCFログインを開始する。
4. 返却された`flow_token`と`subtasks`に従い、`subtask_inputs`を同じエンドポイントへ送る。
5. Androidアプリは`enter_text`、`enter_password`、`choice_selection`、`email_verification`、`phone_verification`、`security_key`、`passkey`、`app_attestation`などを動的に処理する。
6. ログイン済み通信ではOAuth 1.0a HMAC-SHA1署名を使用する。

アプリに埋め込まれたconsumer key/secretは解析領域で存在を確認しましたが、秘密情報としてGit、ログ、ドキュメントへ出力しません。製品はユーザーが提供したAPKMからローカルに取得する経路、または秘密情報を安全に注入する経路を使用します。

## 主要機能の操作

現在の製品プロファイルへ固定した操作には次が含まれます。

- おすすめ: `home_timeline`
- フォロー中: `home_timeline_latest`
- ユーザーポスト: `user_with_profile_tweets_query_v2`
- ユーザー返信: `user_with_profile_tweets_and_replies_query_v2`
- リスト: `list_timeline`
- 履歴: `bookmark_timeline_v2`
- 検索: `search_timeline`
- ポスト詳細: `tweet_result_by_id_query`
- 会話: `conversation_timeline_v2`
- 投稿、削除、いいね解除を含む各Mutation
- リストメンバー追加・削除: `list_member_add` / `list_member_remove`
- フォロー・ミュート・ブロック: `/1.1/friendships/create.json`、`/1.1/mutes/users/create.json`、`/1.1/blocks/create.json`
- Exploreの非ポスト要素: `TimelineTrend`の`name`、`url`、`description`、`rank`、`trendMetadata`
- 通知の非ポスト要素: `TimelineNotification`の`id`、`url`、`socialContext`

## Live Pipeline

`com.twitter.network.livepipeline`の要求・SSEパーサー・event typeを追跡し、次を確認しました。

- 接続: `GET /1.1/live_pipeline/events?topic=...`
- ヘッダー: `Accept: text/event-stream`と通常の認証済みAndroidヘッダー
- topic形式: `/{event_type}/{entity_id}`
- 確認済みevent type: `tweet_engagement`、`dm_update`、`dm_typing`、`live_content`
- SSEの`data:`には`topic`と、event typeをキーに持つ`payload`が入る
- Android側の再接続は500msから16秒までのバックオフ、最大10回

通常のホーム・ユーザー・リストへ新規ポストを配送するevent typeは、この版のレジストリには存在しません。NyTweetDeckは推測topicや定期ポーリングを追加せず、表示中ポストの数字とDMに確認済みtopicを使用します。通常タイムラインは自分の投稿・返信・操作が成功した場合にローカルSSEで更新します。

## 未確定事項

- App Attestationが各エンドポイントで必須になる条件
- OCFが返す二要素認証、Security Key、Passkey、Captchaの実際の組み合わせ
- 実アカウントごとのFeature Switch値とGraphQL`features`入力
- 実アカウントで`tweet_engagement`と`dm_update`を購読した場合のpayload実例

これらは仮値で埋めず、許可された実通信または追加のbytecode解析で確定します。
