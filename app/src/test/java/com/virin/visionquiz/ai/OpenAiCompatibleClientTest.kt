package com.virin.visionquiz.ai

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
    fun streamsCompletionDeltasInOrder() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    : heartbeat

                    data: {"choices":[{"delta":{"role":"assistant"}}]}

                    data: {"choices":[{"delta":{"content":"### 结论\n"}}]}

                    data: {"choices":[{"delta":{"content":"**正确**"}}]}

                    data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.trimIndent()
                )
        )
        val deltas = mutableListOf<String>()

        val result = OpenAiCompatibleClient().completeStreaming(
            config,
            AiPrompt("system", "user"),
            deltas::add
        )

        assertEquals(listOf("### 结论\n", "**正确**"), deltas)
        assertEquals("### 结论\n**正确**", result)
        val request = server.takeRequest()
        val requestBody = request.body.readUtf8()
        assertTrue(requestBody.contains("\"stream\":true"))
        assertTrue(requestBody.contains("\"max_tokens\":1800"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
    }

    @Test(timeout = 10_000)
    fun acceptsStreamEndingAtEofAndIgnoresEmptyEvents() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setBody(
                    """

                    data: {"choices":[{"delta":{"content":"完成"}}]}
                    """.trimIndent()
                )
        )

        val result = OpenAiCompatibleClient().completeStreaming(
            config,
            AiPrompt("system", "user")
        ) {}

        assertEquals("完成", result)
    }

    @Test(timeout = 10_000)
    fun acceptsStreamMessageContentFromCompatibleProviders() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    data: {"choices":[{"message":{"content":"兼容内容"}}]}

                    data: [DONE]
                    """.trimIndent()
                )
        )

        val result = OpenAiCompatibleClient().completeStreaming(
            config,
            AiPrompt("system", "user")
        ) {}

        assertEquals("兼容内容", result)
    }

    @Test(timeout = 10_000)
    fun fallsBackToNormalJsonWhenStreamingIsUnsupported() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"完整结果"}}]}""")
        )
        val deltas = mutableListOf<String>()

        val result = OpenAiCompatibleClient().completeStreaming(
            config,
            AiPrompt("system", "user"),
            deltas::add
        )

        assertEquals("完整结果", result)
        assertEquals(listOf("完整结果"), deltas)
    }

    @Test(timeout = 10_000)
    fun rejectsMalformedOrEmptyStream() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: not-json\n\n")
        )
        val malformed = runCatching {
            OpenAiCompatibleClient().completeStreaming(
                config,
                AiPrompt("system", "user")
            ) {}
        }.exceptionOrNull()

        assertTrue(malformed is IOException)
        assertEquals("API 返回的流式数据格式不正确", malformed?.message)

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n")
        )
        val empty = runCatching {
            OpenAiCompatibleClient().completeStreaming(
                config,
                AiPrompt("system", "user")
            ) {}
        }.exceptionOrNull()

        assertTrue(empty is IOException)
        assertEquals("API 返回了空内容", empty?.message)
    }

    @Test(timeout = 10_000)
    fun exposesErrorInsideSuccessfulStreamResponse() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"error\":{\"message\":\"stream failed\"}}\n\n")
        )

        val error = runCatching {
            OpenAiCompatibleClient().completeStreaming(
                config,
                AiPrompt("system", "user")
            ) {}
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("stream failed", error?.message)
    }

    @Test(timeout = 10_000)
    fun exposesStreamingApiErrorAndSupportsCancellation() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"error":{"message":"rate limited"}}"""
            )
        )
        val apiError = runCatching {
            OpenAiCompatibleClient().completeStreaming(
                config,
                AiPrompt("system", "user")
            ) {}
        }.exceptionOrNull()
        assertTrue(apiError is IOException)
        assertEquals("rate limited", apiError?.message)
        server.takeRequest()

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"迟到\"}}]}\n\n")
                .setBodyDelay(5, TimeUnit.SECONDS)
        )
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            OpenAiCompatibleClient().completeStreaming(
                config,
                AiPrompt("system", "user")
            ) {}
        }
        server.takeRequest()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
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
