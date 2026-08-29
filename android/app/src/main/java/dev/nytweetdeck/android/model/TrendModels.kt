package dev.nytweetdeck.android.model

/** Exploreから取得したトレンドを画面で扱いやすい形に正規化したページ。 */
data class TrendPage(
    val trends: List<Trend>,
    val nextCursor: String?,
)

/** X Web APIのトレンド項目。 */
data class Trend(
    val name: String,
    val description: String?,
    val rank: String?,
    val url: String,
    val domainContext: String?,
    val metaDescription: String?,
)
