package com.ocrapp.di

import com.ocrapp.auth.TokenRequest
import com.ocrapp.ocr.OcrConnection
import com.ocrapp.ocr.RunPodCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which host each request really goes to, through the clients the app builds.
 *
 * The client here answers every request itself, so nothing leaves the machine: the test
 * reads the requests as they would have gone out.
 */
class BackendHostsTest {

    private val sent = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            sent += request
            bodies += Buffer().also { request.body?.writeTo(it) }.readUtf8()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(canned(request.url.encodedPath).toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private val json = AppModule.provideJson()

    @Test
    fun `a managed session goes to the managed host and nowhere else`() = runBlocking {
        AppModule.provideOcrBackends(client, json).route(OcrConnection.Managed("session-1", 4_102_444_800L)).health()

        val request = sent.single()
        assertEquals("https://ocr.robinluiz.fr/v2/ocr/health", request.url.toString())
        assertEquals("Bearer session-1", request.header("Authorization"))
    }

    @Test
    fun `a RunPod key goes to RunPod and nowhere else`() = runBlocking {
        val direct = OcrConnection.Direct(RunPodCredentials("endpoint-1", "key-1"))

        AppModule.provideOcrBackends(client, json).route(direct).health()

        val request = sent.single()
        assertEquals("https://api.runpod.ai/v2/endpoint-1/health", request.url.toString())
        assertEquals("Bearer key-1", request.header("Authorization"))
    }

    // The endpoint id is typed by the user; whatever it contains, it is one path segment.
    @Test
    fun `a typed endpoint id cannot move a request to another host`() = runBlocking {
        val direct = OcrConnection.Direct(RunPodCredentials("@evil.example/x?y=#z", "key-1"))

        AppModule.provideOcrBackends(client, json).route(direct).health()

        assertEquals("api.runpod.ai", sent.single().url.host)
        assertEquals("https", sent.single().url.scheme)
    }

    @Test
    fun `the sign-in talks to the managed host`() = runBlocking {
        val auth = AppModule.provideOcrAuthApi(client, json)

        auth.exchangeCode(TokenRequest("code-1", "v".repeat(43)))
        auth.signOut("Bearer session-1")

        assertEquals(
            listOf("https://ocr.robinluiz.fr/auth/token", "https://ocr.robinluiz.fr/auth/signout"),
            sent.map { it.url.toString() },
        )
        assertEquals(listOf("POST", "POST"), sent.map { it.method })
        assertEquals("""{"code":"code-1","code_verifier":"${"v".repeat(43)}"}""", bodies[0])
        // The exchange has nothing to authenticate with yet; the sign-out names its own session.
        assertEquals(listOf(null, "Bearer session-1"), sent.map { it.header("Authorization") })
    }

    // A redirect would carry a document's pages to a host the user never chose (OkHttp
    // drops the Authorization header across hosts, but not the body). Its default follows
    // one; the managed clients must not.
    @Test
    fun `the managed clients refuse to follow a redirect`() {
        val first = MockWebServer()
        val elsewhere = MockWebServer()
        first.start()
        elsewhere.start()
        try {
            val redirect = MockResponse()
                .setResponseCode(307)
                .setHeader("Location", elsewhere.url("/collected").toString())
            first.enqueue(redirect)
            first.enqueue(redirect)
            elsewhere.enqueue(MockResponse().setResponseCode(200))
            val request = Request.Builder()
                .url(first.url("/v2/ocr/run"))
                .header("Authorization", "Bearer session-1")
                .post("pages".toRequestBody())
                .build()

            OkHttpClient().withoutRedirects().newCall(request).execute().use { response ->
                assertEquals(307, response.code)
            }
            assertEquals(0, elsewhere.requestCount)

            // The control: without the guard, the same answer takes the request away.
            OkHttpClient().newCall(request).execute().close()
            assertEquals(1, elsewhere.requestCount)
        } finally {
            first.shutdown()
            elsewhere.shutdown()
        }
    }

    private fun canned(path: String): String = when {
        path.endsWith("/health") -> """{"workers":{"ready":1,"idle":0,"running":0}}"""
        path == "/auth/token" -> """{"status":"invalid"}"""
        path == "/auth/signout" -> """{"status":"signed_out"}"""
        else -> "{}"
    }
}
