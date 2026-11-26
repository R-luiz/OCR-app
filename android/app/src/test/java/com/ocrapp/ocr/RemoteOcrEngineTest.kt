package com.ocrapp.ocr

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.TimeUnit

/**
 * Drives [RemoteOcrEngine]'s submit-and-poll state machine against a MockWebServer.
 *
 * Robolectric is needed only because encoding a page touches android.graphics.Bitmap
 * and android.util.Base64. Polling waits in real time, so each poll a test goes through
 * costs it about a second.
 */
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
        engine = RemoteOcrEngine(api = apiAt(server.url("/").toString()), credentialsProvider = { credentials })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `polls through queued and running to completed`() = runBlocking {
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
        val result = engine.recognize(FakePageSource(1), onProgress = { stages += it.stage })

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
    fun `returns immediately when the submit call already completed`() = runBlocking {
        server.enqueue(completed("""{"markdown":"warm worker"}"""))

        val output = engine.recognize(FakePageSource(1)).getOrThrow()

        assertEquals("warm worker", output.markdown)
        // One request total: no status poll was needed.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `submits the page as a data url with the bearer token`() = runBlocking {
        server.enqueue(completed("""{"markdown":"x"}"""))

        engine.recognize(FakePageSource(1)).getOrThrow()

        val request = server.takeRequest()
        assertEquals("/v2/endpoint-1/run", request.path)
        assertEquals("Bearer key-1", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body, body.startsWith("""{"input":{"images":["data:image/jpeg;base64,"""))
    }

    @Test
    fun `sends every page of a document in one job`() = runBlocking {
        server.enqueue(completed("""{"markdown":"x"}"""))

        engine.recognize(FakePageSource(3)).getOrThrow()

        val body = server.takeRequest().body.readUtf8()
        assertEquals(3, Regex("data:image/jpeg;base64,").findAll(body).count())
        // One request for all of them — that is what lets the model carry context
        // across a page break.
        assertEquals(1, server.requestCount)
    }

    // One page gets the model's tiled mode, which can use far more pixels than the flat
    // mode several pages get. Sending the wrong size either wastes the tiles or the upload.
    @Test
    fun `renders a single page larger than the pages of a document`() = runBlocking {
        server.enqueue(completed("""{"markdown":"x"}"""))
        server.enqueue(completed("""{"markdown":"x"}"""))
        val single = FakePageSource(1)
        val document = FakePageSource(2)

        engine.recognize(single).getOrThrow()
        engine.recognize(document).getOrThrow()

        assertEquals(listOf(1920), single.requestedEdges)
        assertEquals(listOf(1024, 1024), document.requestedEdges)
    }

    // RunPod refuses a job past its body and page limits, so a document over them has to
    // arrive as consecutive jobs and still come back as one result in page order.
    @Test
    fun `splits a document over the per-job limit into consecutive jobs`() = runBlocking {
        server.enqueue(completed("""{"markdown":"first part"}"""))
        server.enqueue(
            completed("""{"markdown":"","pages":[{"index":0,"markdown":""}],"empty_pages":[0]}"""),
        )

        val output = engine.recognize(FakePageSource(65)).getOrThrow()

        assertEquals(2, server.requestCount)
        assertEquals(64, imageCount(server.takeRequest().body.readUtf8()))
        assertEquals(1, imageCount(server.takeRequest().body.readUtf8()))
        assertEquals("first part", output.markdown)
        assertEquals(65, output.pageCount)
        // The second job's page 0 is the document's page 65.
        assertEquals(listOf(65), output.emptyPageNumbers)
    }

    @Test
    fun `byte-limited jobs still deliver a single result in page order`() = runBlocking {
        val pages = FakePageSource(3)
        val charsPerImage = pages.usePage(0, 1024) { it.toJpegDataUrl().length.toLong() }
        val limited = RemoteOcrEngine(
            api = apiAt(server.url("/").toString()),
            credentialsProvider = { credentials },
            maxJobChars = 2 * charsPerImage,
        )
        server.enqueue(completed("""{"markdown":"first\n\nsecond"}"""))
        server.enqueue(completed("""{"markdown":"third"}"""))

        val output = limited.recognize(pages).getOrThrow()

        assertEquals("first\n\nsecond\n\nthird", output.markdown)
        assertEquals(3, output.pageCount)
        assertEquals(2, imageCount(server.takeRequest().body.readUtf8()))
        assertEquals(1, imageCount(server.takeRequest().body.readUtf8()))
    }

    // A job that ran and failed is a backend error, not an unreachable endpoint: the
    // endpoint answered, so telling the user to check their connection is misleading.
    @Test
    fun `failed job reports a backend error, not unreachable`() = runBlocking {
        server.enqueue(jsonResponse("""{"id":"job-3","status":"IN_QUEUE"}"""))
        server.enqueue(
            jsonResponse("""{"id":"job-3","status":"FAILED","error":"CUDA out of memory"}"""),
        )

        val error = engine.recognize(FakePageSource(1)).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.BACKEND_ERROR, (error as BackendUnavailableException).reason)
        // The worker's own words must survive: they are the only actionable part.
        assertEquals("CUDA out of memory", error.message)
    }

    @Test
    fun `a completed status with a worker error is not a successful empty scan`() = runBlocking {
        server.enqueue(completed("""{"error":"inference failed"}"""))

        val error = engine.recognize(FakePageSource(1)).exceptionOrNull() as BackendUnavailableException

        assertEquals(FallbackReason.BACKEND_ERROR, error.reason)
        assertEquals("inference failed", error.message)
    }

    @Test
    fun `http error reports a backend error rather than propagating`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val error = engine.recognize(FakePageSource(1)).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.BACKEND_ERROR, (error as BackendUnavailableException).reason)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("401"))
    }

    // A phone drops requests while it changes networks, and the job runs on regardless.
    @Test
    fun `a failed poll does not abandon a running job`() = runBlocking {
        server.enqueue(jsonResponse("""{"id":"job-4","status":"IN_QUEUE"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(completed("""{"markdown":"made it"}""", id = "job-4"))

        val output = engine.recognize(FakePageSource(1)).getOrThrow()

        assertEquals("made it", output.markdown)
    }

    // An abandoned job would otherwise wait in the queue or hold a GPU, billing for a
    // result nobody collects.
    @Test
    fun `cancelling a scan cancels its job on RunPod`() = runBlocking {
        server.enqueue(jsonResponse("""{"id":"job-9","status":"IN_QUEUE"}"""))
        server.enqueue(jsonResponse("""{"id":"job-9","status":"IN_QUEUE"}"""))
        server.enqueue(jsonResponse("""{"id":"job-9","status":"CANCELLED"}"""))

        val scan = launch(Dispatchers.Default) { engine.recognize(FakePageSource(1)) }
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) // submit
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) // first poll
        scan.cancelAndJoin()

        val cancel = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("POST", cancel?.method)
        assertEquals("/v2/endpoint-1/cancel/job-9", cancel?.path)
    }

    @Test
    fun `missing credentials report not-configured without any network call`() = runBlocking {
        val unconfigured = RemoteOcrEngine(
            api = apiAt(server.url("/").toString()),
            credentialsProvider = { null },
        )

        val error = unconfigured.recognize(FakePageSource(1)).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.NOT_CONFIGURED, (error as BackendUnavailableException).reason)
        assertEquals(0, server.requestCount)
    }

    // The BACKEND_ERROR/UNREACHABLE split is only worth anything if a real transport
    // failure still reports UNREACHABLE — otherwise every failure would read as though
    // the endpoint had answered.
    @Test
    fun `transport failure still reports unreachable`() = runBlocking {
        val unreachable = RemoteOcrEngine(
            // Port 1 is reserved and nothing listens there, so connecting fails with an
            // IOException rather than any HTTP status.
            api = apiAt("http://127.0.0.1:1/"),
            credentialsProvider = { credentials },
        )

        val error = unreachable.recognize(FakePageSource(1)).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.UNREACHABLE, (error as BackendUnavailableException).reason)
    }

    @Test
    fun `empty document fails fast`() = runBlocking {
        val error = engine.recognize(FakePageSource(0)).exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `falls back to concatenated pages when markdown field is blank`() = runBlocking {
        server.enqueue(
            completed(
                """{"markdown":"",
                    "pages":[{"index":1,"markdown":"second"},{"index":0,"markdown":"first"}]}""",
            ),
        )

        val output = engine.recognize(FakePageSource(2)).getOrThrow()

        assertEquals("first\n\nsecond", output.markdown)
        assertEquals(2, output.pageCount)
    }

    // A short document reads as a complete one, so pages the backend produced nothing
    // for have to reach the UI as data rather than be inferred from the text.
    @Test
    fun `reports pages the backend recognized nothing on`() = runBlocking {
        server.enqueue(
            completed(
                """{"markdown":"a\n\nc",
                    "pages":[{"index":0,"markdown":"a"},{"index":1,"markdown":""},
                             {"index":2,"markdown":"c"}],
                    "empty_pages":[1]}""",
            ),
        )

        val output = engine.recognize(FakePageSource(3)).getOrThrow()

        // 1-based for display, and the page count reflects what was sent.
        assertEquals(listOf(2), output.emptyPageNumbers)
        assertEquals(3, output.pageCount)
    }

    @Test
    fun `reports pages the backend omitted entirely`() = runBlocking {
        // A missing middle page cannot be found just by comparing the page counts.
        server.enqueue(
            completed(
                """{"markdown":"a\n\nc",
                    "pages":[{"index":0,"markdown":"a"},{"index":2,"markdown":"c"}]}""",
            ),
        )

        val output = engine.recognize(FakePageSource(3)).getOrThrow()

        assertEquals(listOf(2), output.emptyPageNumbers)
        assertEquals(3, output.pageCount)
    }

    @Test
    fun `ignores a legacy workers note when no text was recognized`() = runBlocking {
        server.enqueue(completed(
            """{"markdown":"*[page 1: no text recognized]*",
                "pages":[{"index":0,"markdown":""}],"empty_pages":[0]}""",
        ))

        val output = engine.recognize(FakePageSource(1)).getOrThrow()

        assertEquals("", output.plainText)
        assertEquals(listOf(1), output.emptyPageNumbers)
    }

    // With the whole document in one entry there are no page boundaries to count, so a
    // one-entry list must not be read as "the other pages went missing".
    @Test
    fun `an unsplit long-context document reports nothing missing`() = runBlocking {
        server.enqueue(
            completed(
                """{"markdown":"whole document","page_mode":"long_context",
                    "pages":[{"index":0,"markdown":"whole document"}]}""",
            ),
        )

        val output = engine.recognize(FakePageSource(3)).getOrThrow()

        assertEquals(emptyList<Int>(), output.emptyPageNumbers)
    }

    @Test
    fun `passes on what the backend warned about`() = runBlocking {
        server.enqueue(completed("""{"markdown":"a","warnings":["parsed page by page instead"]}"""))

        val output = engine.recognize(FakePageSource(1)).getOrThrow()

        assertEquals(listOf("parsed page by page instead"), output.warnings)
    }

    private fun apiAt(baseUrl: String): RunPodApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(
            Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()),
        )
        .build()
        .create(RunPodApi::class.java)

    private fun completed(output: String, id: String = "j") =
        jsonResponse("""{"id":"$id","status":"COMPLETED","output":$output}""")

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun imageCount(body: String): Int =
        Regex("data:image/jpeg;base64,").findAll(body).count()
}
