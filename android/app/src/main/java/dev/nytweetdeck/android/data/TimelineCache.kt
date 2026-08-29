package dev.nytweetdeck.android.data

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class TimelineCache(private val directory: File) {
    fun read(accountId: String, kind: String, target: String?): String? {
        val file = cacheFile(accountId, kind, target)
        if (!file.isFile || file.length() !in 1..MAX_CACHE_ENTRY_BYTES.toLong()) return null
        return runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
    }

    fun write(accountId: String, kind: String, target: String?, response: String) {
        val bytes = response.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size in 1..MAX_CACHE_ENTRY_BYTES) { "タイムラインキャッシュが大きすぎます。" }
        require(directory.isDirectory || directory.mkdirs()) { "タイムラインキャッシュを作成できません。" }
        val targetFile = cacheFile(accountId, kind, target)
        val temporary = File(targetFile.path + ".tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(targetFile)) {
                targetFile.delete()
                require(temporary.renameTo(targetFile)) { "タイムラインキャッシュを置換できません。" }
            }
        } finally {
            temporary.delete()
        }
    }

    fun clearAccount(accountId: String) {
        val prefix = digest(accountId) + "-"
        directory.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach(File::delete)
    }

    private fun cacheFile(accountId: String, kind: String, target: String?): File {
        require(accountId.matches(Regex("[0-9]{1,24}"))) { "XアカウントID形式が不正です。" }
        require(kind.matches(Regex("[A-Za-z][A-Za-z0-9]{0,79}"))) { "タイムライン種別が不正です。" }
        require(target == null || target.length <= 500) { "タイムライン対象が長すぎます。" }
        return File(directory, "${digest(accountId)}-${digest("$kind\u0000${target.orEmpty()}")}.json")
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val MAX_CACHE_ENTRY_BYTES = 8 * 1024 * 1024
    }
}
