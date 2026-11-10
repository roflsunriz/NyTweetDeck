package dev.nytweetdeck.android.xapi.live

import java.util.concurrent.Executor
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePipelineConnectorTest {
    @Test
    fun closingAnActivityNeverClosesTheNetworkBodyOnTheCallingThread() {
        val tasks = mutableListOf<Runnable>()
        var bodyClosed = false
        val body = object : ResponseBody() {
            private val buffered = object : ForwardingSource(Buffer().writeUtf8("data: fixture\n")) {
                override fun close() {
                    bodyClosed = true
                    super.close()
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
        val connector = OkHttpLivePipelineConnector(client, Executor { tasks.add(it) })
        val stream = connector.connect(Request.Builder().url("https://api.x.com/fixture").build())

        stream.close()
        stream.close()

        assertFalse(bodyClosed)
        assertEquals(1, tasks.size)
        tasks.single().run()
        assertTrue(bodyClosed)
    }
}
