package com.virin.visionquiz.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class OpenAiCompatibleClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {
    suspend fun complete(config: AiConfig, prompt: AiPrompt): String {
        val endpoint = AiEndpointValidator.buildEndpoint(config.baseUrl).getOrThrow()
        val request = completionRequest(endpoint, config, prompt, stream = false)

        return execute(request, ::parseCompletionResponse)
    }

    suspend fun completeStreaming(
        config: AiConfig,
        prompt: AiPrompt,
        onDelta: (String) -> Unit
    ): String {
        val endpoint = AiEndpointValidator.buildEndpoint(config.baseUrl).getOrThrow()
        val request = completionRequest(endpoint, config, prompt, stream = true)
        return execute(request) { response ->
            parseStreamingResponse(response, onDelta)
        }
    }

    suspend fun listModels(baseUrl: String, apiKey: String): List<String> {
        val endpoint = AiEndpointValidator.buildModelsEndpoint(baseUrl).getOrThrow()
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .get()
            .build()
        return execute(request, ::parseModelsResponse)
    }

    private fun parseCompletionResponse(response: Response): String {
        val responseText = response.body?.string().orEmpty()
        ensureSuccessful(response, responseText)
        val content = parseCompletionContent(responseText).trim()
        if (content.isBlank()) throw IOException("API 返回了空内容")
        return content
    }

    private fun parseStreamingResponse(
        response: Response,
        onDelta: (String) -> Unit
    ): String {
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        if (!response.isSuccessful || !contentType.contains("text/event-stream")) {
            val responseText = response.body?.string().orEmpty()
            ensureSuccessful(response, responseText)
            val content = parseCompletionContent(responseText).trim()
            if (content.isBlank()) throw IOException("API 返回了空内容")
            onDelta(content)
            return content
        }

        val source = response.body?.source() ?: throw IOException("API 返回了空内容")
        val content = StringBuilder()
        val eventData = mutableListOf<String>()

        fun dispatchEvent(): Boolean {
            if (eventData.isEmpty()) return false
            val payload = eventData.joinToString("\n").trim()
            eventData.clear()
            if (payload.isBlank()) return false
            if (payload == "[DONE]") return true
            val delta = parseStreamDelta(payload)
            if (delta.isNotEmpty()) {
                content.append(delta)
                onDelta(delta)
            }
            return false
        }

        var done = false
        while (!done) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isBlank() -> done = dispatchEvent()
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> eventData += line.removePrefix("data:").trimStart()
            }
        }
        if (!done) dispatchEvent()
        if (content.isEmpty()) throw IOException("API 返回了空内容")
        return content.toString()
    }

    private fun parseCompletionContent(responseText: String): String {
        return runCatching {
            gson.fromJson(responseText, JsonObject::class.java)
                .getAsJsonArray("choices")
                ?.get(0)
                ?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")
                ?.asString
        }.getOrNull().orEmpty()
    }

    private fun parseStreamDelta(payload: String): String {
        val root = try {
            gson.fromJson(payload, JsonObject::class.java)
        } catch (error: Exception) {
            throw IOException("API 返回的流式数据格式不正确", error)
        }
        root.getAsJsonObject("error")
            ?.get("message")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.let { throw IOException(it.take(240)) }
        return runCatching {
            root.getAsJsonArray("choices")
                ?.get(0)
                ?.asJsonObject
                ?.getAsJsonObject("delta")
                ?.get("content")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
        }.getOrNull().orEmpty()
    }

    private fun parseModelsResponse(response: Response): List<String> {
        val responseText = response.body?.string().orEmpty()
        ensureSuccessful(response, responseText)
        return try {
            val data = gson.fromJson(responseText, JsonObject::class.java)
                .getAsJsonArray("data")
                ?: throw IOException("API 返回的模型列表格式不正确")
            data.mapNotNull { element ->
                    element.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.get("id")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }
                .distinct()
                .sorted()
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException("API 返回的模型列表格式不正确", error)
        }
    }

    private fun ensureSuccessful(response: Response, responseText: String) {
        if (response.isSuccessful) return
        val apiMessage = runCatching {
            gson.fromJson(responseText, JsonObject::class.java)
                .getAsJsonObject("error")
                ?.get("message")
                ?.asString
        }.getOrNull()
        throw IOException(apiMessage?.take(240) ?: "API 请求失败：HTTP ${response.code}")
    }

    private suspend fun <T> execute(
        request: Request,
        parser: (Response) -> T
    ): T {
        val result = suspendCancellableCoroutine<Result<T>> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        continuation.resume(runCatching { parser(it) })
                    }
                }
            })
        }
        return result.getOrThrow()
    }

    private fun message(role: String, content: String): JsonObject {
        return JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }
    }

    private fun completionRequest(
        endpoint: String,
        config: AiConfig,
        prompt: AiPrompt,
        stream: Boolean
    ): Request {
        val body = JsonObject().apply {
            addProperty("model", config.model.trim())
            addProperty("temperature", 0.3)
            addProperty("stream", stream)
            addProperty("max_tokens", 1000)
            add("messages", JsonArray().apply {
                add(message("system", prompt.system))
                add(message("user", prompt.user))
            })
        }
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .apply {
                if (stream) header("Accept", "text/event-stream")
            }
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
