package dev.nytweetdeck.android.xapi.live

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

interface LivePipelineConnector {
    fun connect(request: Request): LivePipelineStream

    fun updateSubscriptions(request: Request)
}

interface LivePipelineStream : AutoCloseable {
    fun readDataLines(consumer: (String) -> Unit)

    override fun close()
}

class LivePipelineHttpException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class OkHttpLivePipelineConnector(
    client: OkHttpClient,
) : LivePipelineConnector {
    private val client = client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun connect(request: Request): LivePipelineStream {
        val call = client.newCall(request)
        val response = try {
            call.execute()
        } catch (exception: IOException) {
            throw LivePipelineHttpException(0, "Live Pipeline接続の通信に失敗しました。", exception)
        }
        if (response.code != 200) {
            response.close()
            throw LivePipelineHttpException(
                response.code,
                "Live Pipeline接続に失敗しました。HTTP ${response.code}",
            )
        }
        return OkHttpLivePipelineStream(call, response)
    }

    override fun updateSubscriptions(request: Request) {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw LivePipelineHttpException(
                        response.code,
                        "Live Pipeline購読更新に失敗しました。HTTP ${response.code}",
                    )
                }
            }
        } catch (exception: LivePipelineHttpException) {
            throw exception
        } catch (exception: IOException) {
            throw LivePipelineHttpException(0, "Live Pipeline購読更新の通信に失敗しました。", exception)
        }
    }

    private class OkHttpLivePipelineStream(
        private val call: okhttp3.Call,
        private val response: Response,
    ) : LivePipelineStream {
        private val closed = AtomicBoolean()

        override fun readDataLines(consumer: (String) -> Unit) {
            try {
                response.body.source().use { source ->
                    while (!closed.get()) {
                        val line = source.readUtf8Line() ?: return
                        val trimmed = line.trim()
                        if (trimmed.startsWith("data:", ignoreCase = true) && trimmed.length > 5) {
                            consumer(trimmed.substring(5).trim())
                        }
                    }
                }
            } catch (exception: IOException) {
                if (!closed.get()) {
                    throw LivePipelineHttpException(0, "Live Pipelineイベント受信に失敗しました。", exception)
                }
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                call.cancel()
                response.close()
            }
        }
    }
}
