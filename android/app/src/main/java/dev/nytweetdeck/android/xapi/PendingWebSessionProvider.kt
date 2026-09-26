package dev.nytweetdeck.android.xapi

/**
 * 保存済みセッションを優先し、ログイン検証中の未保存セッションへフォールバックする。
 *
 * 検証時点ではアカウントがまだ保存されていないため、署名生成や定義更新が
 * 保存先だけを見るとセッションなしと誤判定する。これを防ぐために検証中の
 * 資格情報を一時的に差し出す。検証終了後は必ず保存済みへ戻す。
 */
class PendingWebSessionProvider(
    private val stored: () -> XSessionCredentials?,
) {
    @Volatile
    private var pending: XSessionCredentials? = null

    fun current(): XSessionCredentials? = pending ?: stored()

    fun <T> runWith(session: XSessionCredentials, block: () -> T): T {
        val previous = pending
        pending = session
        try {
            return block()
        } finally {
            pending = previous
        }
    }
}
