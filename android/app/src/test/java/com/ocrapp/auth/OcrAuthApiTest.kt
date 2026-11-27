package com.ocrapp.auth

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.ocrapp.di.AppModule
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit

/**
 * The managed service's sign-in wire contract, as the app sends and reads it, over
 * the app's own JSON configuration.
 */
class OcrAuthApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: OcrAuthApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(AppModule.provideJson().asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OcrAuthApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the exchange posts the code and the verifier, as json, to auth token`() = runBlocking {
        server.enqueue(json("""{"status":"invalid"}"""))

        api.exchangeCode(TokenRequest("code-1", VERIFIER))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/token", request.path)
        assertEquals("""{"code":"code-1","code_verifier":"$VERIFIER"}""", request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        // The only header the app adds is the content type: no session exists yet.
        assertEquals(null, request.getHeader("Authorization"))
    }

    @Test
    fun `reads an approval with the session and its expiry in unix seconds`() = runBlocking {
        server.enqueue(
            json("""{"status":"authorized","token":"t0k3n","expires_at":1800604800,"future_field":true}"""),
        )

        val reply = api.exchangeCode(TokenRequest("code-1", VERIFIER))

        assertEquals(TokenResponse(TokenResponse.AUTHORIZED, token = "t0k3n", expiresAt = 1_800_604_800L), reply)
    }

    @Test
    fun `reads the refusals`() = runBlocking {
        server.enqueue(json("""{"status":"forbidden"}"""))
        server.enqueue(json("""{"status":"invalid"}"""))

        assertEquals(TokenResponse(TokenResponse.FORBIDDEN), api.exchangeCode(TokenRequest("code-1", VERIFIER)))
        assertEquals(TokenResponse(TokenResponse.INVALID), api.exchangeCode(TokenRequest("code-1", VERIFIER)))
    }

    @Test
    fun `any other status code is an error, not a reply`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"status":"authorized","token":"t"}"""))

        val error = assertThrows(HttpException::class.java) {
            runBlocking { api.exchangeCode(TokenRequest("code-1", VERIFIER)) }
        }

        assertEquals(429, error.code())
    }

    @Test
    fun `signing out posts the session, and nothing else, to auth signout`() = runBlocking {
        server.enqueue(json("""{"status":"signed_out"}"""))

        api.signOut("Bearer session-1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/auth/signout", request.path)
        assertEquals("Bearer session-1", request.getHeader("Authorization"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `the secrets of an exchange stay out of logs`() {
        val text = TokenRequest("code-1", VERIFIER).toString() +
            TokenResponse(TokenResponse.AUTHORIZED, token = "t0k3n", expiresAt = 1L).toString()

        assertFalse(text.contains("code-1"))
        assertFalse(text.contains(VERIFIER))
        assertFalse(text.contains("t0k3n"))
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    }
}
