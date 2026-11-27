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

    // Two hosts, as in the app: `server` stands in for api.runpod.ai (the user's own
    // endpoint) and `managedServer` for the managed service.
    private lateinit var server: MockWebServer
    private lateinit var managedServer: MockWebServer
    private lateinit var backends: OcrBackends
    private lateinit var connections: FakeConnections
    private lateinit var engine: RemoteOcrEngine

    private val credentials = RunPodCredentials(endpointId = "endpoint-1", apiKey = "key-1")
    private val direct = OcrConnection.Direct(credentials)
    private val managed = OcrConnection.Managed("session-1", expiresAtSeconds = 4_102_444_800L)

    /** The engine's own per-job limit, for the tests that set its clock. */
    private val jobChars = 9L * 1024 * 1024

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        managedServer = MockWebServer()
        managedServer.start()
        backends = OcrBackends(
            runPod = apiAt(server.url("/").toString()),
            managed = apiAt(managedServer.url("/").toString()),
        )
        connections = FakeConnections(direct)
        engine = RemoteOcrEngine(backends, connections)
    }

    @After
    fun tearDown() {
        server.shutdown()
        managedServer.shutdown()
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
        val limited = RemoteOcrEngine(backends, connections, maxJobChars = 2 * charsPerImage)
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
        // Only a managed session can be "rejected": a wrong RunPod key is the user's to fix.
        assertEquals(emptyList<OcrConnection.Managed>(), connections.rejected)
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
        val unconfigured = RemoteOcrEngine(backends, FakeConnections(listOf(ConnectionLookup.NotConfigured)))

        val error = unconfigured.recognize(FakePageSource(1)).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.NOT_CONFIGURED, (error as BackendUnavailableException).reason)
        assertEquals(0, server.requestCount)
        assertEquals(0, managedServer.requestCount)
    }

    // The BACKEND_ERROR/UNREACHABLE split is only worth anything if a real transport
    // failure still reports UNREACHABLE — otherwise every failure would read as though
    // the endpoint had answered.
    @Test
    fun `transport failure still reports unreachable`() = runBlocking {
        val unreachable = RemoteOcrEngine(
            // Port 1 is reserved and nothing listens there, so connecting fails with an
            // IOException rather than any HTTP status.
            OcrBackends(runPod = apiAt("http://127.0.0.1:1/"), managed = apiAt("http://127.0.0.1:1/")),
            connections,
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

    // Where a scan goes, and with which credential, follows from its connection alone:
    // neither credential may ever reach the other host.
    @Test
    fun `a direct connection reaches only RunPod`() = runBlocking {
        server.enqueue(completed("""{"markdown":"x"}"""))

        engine.recognize(FakePageSource(1)).getOrThrow()

        val request = server.takeRequest()
        assertEquals("/v2/endpoint-1/run", request.path)
        assertEquals("Bearer key-1", request.getHeader("Authorization"))
        assertEquals(0, managedServer.requestCount)
    }

    @Test
    fun `a managed connection reaches only the managed service`() = runBlocking {
        managedServer.enqueue(completed("""{"markdown":"managed"}"""))
        val managedEngine = RemoteOcrEngine(backends, FakeConnections(managed))

        val output = managedEngine.recognize(FakePageSource(1)).getOrThrow()

        assertEquals("managed", output.markdown)
        val request = managedServer.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v2/ocr/run", request.path)
        assertEquals("Bearer session-1", request.getHeader("Authorization"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a managed job is polled and cancelled on the managed service too`() = runBlocking {
        managedServer.enqueue(jsonResponse("""{"id":"job-7","status":"IN_QUEUE"}"""))
        managedServer.enqueue(jsonResponse("""{"id":"job-7","status":"IN_QUEUE"}"""))
        managedServer.enqueue(jsonResponse("""{"id":"job-7","status":"CANCELLED"}"""))
        val managedEngine = RemoteOcrEngine(backends, FakeConnections(managed))

        val scan = launch(Dispatchers.Default) { managedEngine.recognize(FakePageSource(1)) }
        val submit = requireNotNull(managedServer.takeRequest(5, TimeUnit.SECONDS))
        val poll = requireNotNull(managedServer.takeRequest(5, TimeUnit.SECONDS))
        scan.cancelAndJoin()
        val cancel = requireNotNull(managedServer.takeRequest(5, TimeUnit.SECONDS))

        assertEquals("/v2/ocr/run", submit.path)
        assertEquals("GET", poll.method)
        assertEquals("/v2/ocr/status/job-7", poll.path)
        assertEquals("POST", cancel.method)
        assertEquals("/v2/ocr/cancel/job-7", cancel.path)
        for (request in listOf(submit, poll, cancel)) {
            assertEquals("Bearer session-1", request.getHeader("Authorization"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a connection test asks the host of that connection only`() = runBlocking {
        server.enqueue(jsonResponse("""{"workers":{"ready":2,"idle":1,"running":0}}"""))
        managedServer.enqueue(jsonResponse("""{"workers":{"ready":1,"idle":0,"running":0}}"""))

        val directHealth = engine.testConnection(direct).getOrThrow()
        val managedHealth = engine.testConnection(managed).getOrThrow()

        assertEquals(2, directHealth.workers?.ready)
        assertEquals(1, managedHealth.workers?.ready)
        val directRequest = server.takeRequest()
        assertEquals("/v2/endpoint-1/health", directRequest.path)
        assertEquals("Bearer key-1", directRequest.getHeader("Authorization"))
        val managedRequest = managedServer.takeRequest()
        assertEquals("/v2/ocr/health", managedRequest.path)
        assertEquals("Bearer session-1", managedRequest.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
        assertEquals(1, managedServer.requestCount)
    }

    // The settings can change while a long document is still uploading, so a scan reads
    // its connection once: every job of one document goes to the same place with the
    // same credential.
    @Test
    fun `a document of several jobs keeps the connection it started with`() = runBlocking {
        // A second lookup would move the second job to the other backend.
        val changing = FakeConnections(
            listOf(ConnectionLookup.Available(managed), ConnectionLookup.Available(direct)),
        )
        managedServer.enqueue(completed("""{"markdown":"first part"}"""))
        managedServer.enqueue(completed("""{"markdown":"second part"}"""))

        val output = RemoteOcrEngine(backends, changing).recognize(FakePageSource(65)).getOrThrow()

        assertEquals("first part\n\nsecond part", output.markdown)
        assertEquals(1, changing.lookups)
        assertEquals(2, managedServer.requestCount)
        assertEquals(0, server.requestCount)
    }

    // The gateway answers 401 for a session it does not accept, and only for that.
    @Test
    fun `a refused session is reported as expired and forgotten`() = runBlocking {
        val provider = FakeConnections(managed)
        managedServer.enqueue(MockResponse().setResponseCode(401).setBody("refused"))

        val error = RemoteOcrEngine(backends, provider).recognize(FakePageSource(1)).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.SESSION_EXPIRED, (error as BackendUnavailableException).reason)
        assertEquals(listOf(managed), provider.rejected)
        // And it never turned into a request to RunPod.
        assertEquals(0, server.requestCount)
    }

    // 502 and 503 say the gateway or its upstream is unwell, not that the user's session
    // is: throwing the session away would make them sign in again for nothing.
    @Test
    fun `a managed server-side failure is a backend error and keeps the session`() = runBlocking {
        for (code in listOf(403, 500, 502, 503)) {
            val provider = FakeConnections(managed)
            managedServer.enqueue(MockResponse().setResponseCode(code).setBody("unwell"))

            val error = RemoteOcrEngine(backends, provider).recognize(FakePageSource(1)).exceptionOrNull()

            assertTrue("HTTP $code", error is BackendUnavailableException)
            assertEquals("HTTP $code", FallbackReason.BACKEND_ERROR, (error as BackendUnavailableException).reason)
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(code.toString()))
            assertEquals("HTTP $code", emptyList<OcrConnection.Managed>(), provider.rejected)
        }
    }

    @Test
    fun `an expired session asks for a new sign-in without any network call`() = runBlocking {
        val expired = FakeConnections(listOf(ConnectionLookup.SessionExpired))

        val error = RemoteOcrEngine(backends, expired).recognize(FakePageSource(1)).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.SESSION_EXPIRED, (error as BackendUnavailableException).reason)
        assertEquals(0, server.requestCount)
        assertEquals(0, managedServer.requestCount)
    }

    @Test
    fun `a session refused while polling expires the scan and still cancels the job`() = runBlocking {
        managedServer.enqueue(jsonResponse("""{"id":"job-5","status":"IN_QUEUE"}"""))
        managedServer.enqueue(MockResponse().setResponseCode(401))
        managedServer.enqueue(MockResponse().setResponseCode(401))
        val provider = FakeConnections(managed)

        val error = RemoteOcrEngine(backends, provider).recognize(FakePageSource(1)).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.SESSION_EXPIRED, (error as BackendUnavailableException).reason)
        // Reported once, by the poll; the cancel that follows is only best effort.
        assertEquals(listOf(managed), provider.rejected)
        requireNotNull(managedServer.takeRequest(5, TimeUnit.SECONDS)) // submit
        requireNotNull(managedServer.takeRequest(5, TimeUnit.SECONDS)) // refused poll
        assertEquals("/v2/ocr/cancel/job-5", managedServer.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals(0, server.requestCount)
    }

    // The service would still take the job, but the session could end before the job does,
    // leaving it with nobody able to collect or cancel it.
    @Test
    fun `a session inside the margin sends no job`() = runBlocking {
        val provider = FakeConnections(managed)
        val ending = RemoteOcrEngine(backends, provider, jobChars) {
            managed.expiresAtSeconds - ManagedSession.MIN_REMAINING_SECONDS
        }

        val error = ending.recognize(FakePageSource(1)).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.SESSION_EXPIRED, (error as BackendUnavailableException).reason)
        assertEquals(0, managedServer.requestCount)
        // Not refused by the service, so not reported as such.
        assertEquals(emptyList<OcrConnection.Managed>(), provider.rejected)
    }

    // A long document's later jobs start later: the margin is checked again for each.
    @Test
    fun `each job of a document checks the session before it is sent`() = runBlocking {
        val provider = FakeConnections(managed)
        val lastUsable = managed.expiresAtSeconds - ManagedSession.MIN_REMAINING_SECONDS - 1
        val clock = ArrayDeque(listOf(lastUsable, lastUsable + 1))
        val late = RemoteOcrEngine(backends, provider, jobChars) { clock.removeFirst() }
        managedServer.enqueue(completed("""{"markdown":"first part"}"""))

        val error = late.recognize(FakePageSource(65)).exceptionOrNull()

        assertTrue(error is BackendUnavailableException)
        assertEquals(FallbackReason.SESSION_EXPIRED, (error as BackendUnavailableException).reason)
        assertEquals(1, managedServer.requestCount)
        assertEquals(1, provider.lookups)
        assertEquals(emptyList<OcrConnection.Managed>(), provider.rejected)
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
