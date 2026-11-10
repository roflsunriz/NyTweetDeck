package dev.nytweetdeck.android

import android.os.Looper
import android.os.NetworkOnMainThreadException
import androidx.test.platform.app.InstrumentationRegistry
import dev.nytweetdeck.android.xapi.live.OkHttpLivePipelineConnector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePipelineCloseUiTest {
    @Test
    fun foregroundStopCanCloseAnSseBodyThatRequiresNetworkIo() {
        val closed = CountDownLatch(1)
        val closeThread = AtomicReference<Thread>()
        val body = object : ResponseBody() {
            private val buffered = object : ForwardingSource(Buffer().writeUtf8("data: fixture\n")) {
                override fun close() {
                    // TLS close on the captured device had exactly this main-thread constraint.
                    if (Looper.myLooper() == Looper.getMainLooper()) throw NetworkOnMainThreadException()
                    closeThread.set(Thread.currentThread())
                    super.close()
                    closed.countDown()
                }
            }.buffer()
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = buffered
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()
        val stream = OkHttpLivePipelineConnector(client).connect(
            Request.Builder().url("https://api.x.com/fixture").build(),
        )
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            stream.close()
            stream.close()
        }
        assertTrue(closed.await(5L, TimeUnit.SECONDS))
        assertNotEquals(Looper.getMainLooper().thread, closeThread.get())
    }
}
