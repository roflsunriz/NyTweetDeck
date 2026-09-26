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

/** トレンド一覧の絞り込み。空文字では全件返す。 */
fun filterTrends(trends: List<Trend>, query: String): List<Trend> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return trends.toList()
    return trends.filter { trend ->
        listOf(trend.name, trend.description, trend.domainContext, trend.metaDescription)
            .filterNotNull()
            .any { it.contains(normalized, ignoreCase = true) }
    }
}
