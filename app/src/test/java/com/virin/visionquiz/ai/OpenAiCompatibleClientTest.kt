package com.virin.visionquiz.ai

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleClientTest {
    private lateinit var server: MockWebServer
    private lateinit var config: AiConfig

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = AiConfig(
            enabled = true,
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            model = "test-model",
            analysisPrompt = "a",
            techniquePrompt = "b",
            mnemonicPrompt = "c"
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test(timeout = 10_000)
    fun parsesSuccessfulCompletion() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"  测试解析  "}}]}"""
            )
        )
        val result = OpenAiCompatibleClient().complete(config, AiPrompt("system", "user"))
        assertEquals("测试解析", result)
        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("/v1/chat/completions", request.path)
    }

    @Test(timeout = 10_000)
    fun exposesApiErrorWithoutCredentials() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"error":{"message":"invalid key"}}"""
            )
        )
        val error = runCatching {
            OpenAiCompatibleClient().complete(config, AiPrompt("system", "user"))
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("invalid key", error?.message)
    }

    @Test(timeout = 10_000)
    fun rejectsMalformedOrEmptyResponse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val error = runCatching {
            OpenAiCompatibleClient().complete(config, AiPrompt("system", "user"))
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals("API 返回了空内容", error?.message)
    }

    @Test(timeout = 10_000)
    fun listsModelsWithAuthenticationAndNormalizesResults() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "data": [
                    {"id": "model-z"},
                    {"id": " model-a "},
                    {"id": ""},
                    {"id": "model-z"},
                    {"object": "model"}
                  ]
                }
                """.trimIndent()
            )
        )

        val models = OpenAiCompatibleClient().listModels(
            server.url("/v1/chat/completions").toString(),
            " test-key "
        )

        assertEquals(listOf("model-a", "model-z"), models)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
    }

    @Test(timeout = 10_000)
    fun returnsEmptyModelListWhenApiHasNoModels() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":[]}""")
        )

        val models = OpenAiCompatibleClient().listModels(
            server.url("/").toString(),
            "test-key"
        )

        assertTrue(models.isEmpty())
    }

    @Test(timeout = 10_000)
    fun rejectsMalformedModelListResponse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val error = runCatching {
            OpenAiCompatibleClient().listModels(
                server.url("/").toString(),
                "test-key"
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("API 返回的模型列表格式不正确", error?.message)
    }

    @Test(timeout = 10_000)
    fun exposesModelListApiError() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"message":"models unavailable"}}"""
            )
        )

        val error = runCatching {
            OpenAiCompatibleClient().listModels(
                server.url("/v1").toString(),
                "test-key"
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("models unavailable", error?.message)
    }
}
