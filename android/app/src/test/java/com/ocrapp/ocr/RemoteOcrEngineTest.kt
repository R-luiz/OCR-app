package com.ocrapp.ocr

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit

/**
 * Drives [RemoteOcrEngine]'s submit-and-poll state machine against a MockWebServer.
 *
 * Robolectric is needed only because [ImageNormalizer] touches android.graphics.Bitmap
 * and android.util.Base64.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteOcrEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: RemoteOcrEngine

    private val credentials = RunPodCredentials(endpointId = "endpoint-1", apiKey = "key-1")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(RunPodApi::class.java)

        engine = RemoteOcrEngine(
            api = api,
            credentialsProvider = { credentials },
            normalizer = ImageNormalizer(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `polls through queued and running to completed`() = runTest {
        server.enqueue(jsonResponse("""{"id":"job-1","status":"IN_QUEUE"}"""))
        server.enqueue(jsonResponse("""{"id":"job-1","status":"IN_QUEUE"}"""))
        server.enqueue(jsonResponse("""{"id":"job-1","status":"IN_PROGRESS"}"""))
        server.enqueue(
            jsonResponse(
                """
                {"id":"job-1","status":"COMPLETED","output":{
                  "markdown":"# Title\n\nBody",
                  "pages":[{"index":0,"markdown":"# Title\n\nBody"}]
                }}
                """.trimIndent(),
            ),
        )

        val stages = mutableListOf<OcrStage>()
        val result = engine.recognize(listOf(testPage()), onStage = { stages += it })

        val output = result.getOrThrow()
        assertEquals("# Title\n\nBody", output.markdown)
        assertEquals("Title\n\nBody", output.plainText)
        assertEquals(EngineType.DOCUMENT, output.engine)
        assertEquals(1, output.pageCount)

        assertEquals(
            listOf(OcrStage.PREPARING, OcrStage.UPLOADING, OcrStage.QUEUED, OcrStage.RUNNING),
            stages,
        )
    }

    @Test
    fun `returns immediately when the submit call already completed`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"id":"job-2","status":"COMPLETED","output":{"markdown":"warm worker"}}""",
            ),
        )

        val output = engine.recognize(listOf(testPage())).getOrThrow()

        assertEquals("warm worker", output.markdown)
        // One request total: no status poll was needed.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `sends gundam config and bearer token for a single page`() = runTest {
        server.enqueue(
            jsonResponse("""{"id":"j","status":"COMPLETED","output":{"markdown":"x"}}"""),
        )

        engine.recognize(listOf(testPage())).getOrThrow()

        val request = server.takeRequest()
        assertEquals("/v2/endpoint-1/run", request.path)
        assertEquals("Bearer key-1", request.getHeader("Authorization"))

        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"mode\":\"single\""))
        assertTrue(body, body.contains("\"image_size\":640"))
        assertTrue(body, body.contains("\"crop_mode\":true"))
    }

    @Test
    fun `sends multi config for several pages in one job`() = runTest {
        server.enqueue(
            jsonResponse("""{"id":"j","status":"COMPLETED","output":{"markdown":"x"}}"""),
        )

        engine.recognize(List(3) { testPage(it) }).getOrThrow()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"mode\":\"multi\""))
        assertTrue(body, body.contains("\"image_size\":1024"))
        assertTrue(body, body.contains("\"crop_mode\":false"))
        // All pages travel in a single request — that is the R-SWA long-horizon path.
        assertEquals(1, server.requestCount)
    }

    // A job that ran and failed is a backend error, not an unreachable endpoint: the
    // endpoint answered, so telling the user to check their connection is misleading.
    @Test
    fun `failed job reports a backend error, not unreachable`() = runTest {
        server.enqueue(jsonResponse("""{"id":"job-3","status":"IN_QUEUE"}"""))
        server.enqueue(
            jsonResponse("""{"id":"job-3","status":"FAILED","error":"CUDA out of memory"}"""),
        )

        val error = engine.recognize(listOf(testPage())).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.BACKEND_ERROR, (error as BackendUnavailableException).reason)
        // The worker's own words must survive: they are the only actionable part.
        assertEquals("CUDA out of memory", error.message)
    }

    @Test
    fun `http error reports a backend error rather than propagating`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val error = engine.recognize(listOf(testPage())).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.BACKEND_ERROR, (error as BackendUnavailableException).reason)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("401"))
    }

    @Test
    fun `missing credentials report not-configured without any network call`() = runTest {
        val unconfigured = RemoteOcrEngine(
            api = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(
                    Json.asConverterFactory("application/json".toMediaType()),
                )
                .build()
                .create(RunPodApi::class.java),
            credentialsProvider = { null },
            normalizer = ImageNormalizer(),
        )

        val error = unconfigured.recognize(listOf(testPage())).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.NOT_CONFIGURED, (error as BackendUnavailableException).reason)
        assertEquals(0, server.requestCount)
    }

    // The BACKEND_ERROR/UNREACHABLE split is only worth anything if a real transport
    // failure still reports UNREACHABLE — otherwise every failure would read as though
    // the endpoint had answered.
    @Test
    fun `transport failure still reports unreachable`() = runTest {
        val unreachable = RemoteOcrEngine(
            api = Retrofit.Builder()
                // Port 1 is reserved and nothing listens there, so connecting fails
                // with an IOException rather than any HTTP status.
                .baseUrl("http://127.0.0.1:1/")
                .addConverterFactory(
                    Json.asConverterFactory("application/json".toMediaType()),
                )
                .build()
                .create(RunPodApi::class.java),
            credentialsProvider = { RunPodCredentials("endpoint", "key") },
            normalizer = ImageNormalizer(),
        )

        val error = unreachable.recognize(listOf(testPage())).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.UNREACHABLE, (error as BackendUnavailableException).reason)
    }

    @Test
    fun `empty page list fails fast`() = runTest {
        val error = engine.recognize(emptyList()).exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `falls back to concatenated pages when markdown field is blank`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {"id":"j","status":"COMPLETED","output":{
                  "markdown":"",
                  "pages":[{"index":1,"markdown":"second"},{"index":0,"markdown":"first"}]
                }}
                """.trimIndent(),
            ),
        )

        val output = engine.recognize(List(2) { testPage(it) }).getOrThrow()

        assertEquals("first\n\nsecond", output.markdown)
        assertEquals(2, output.pageCount)
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
