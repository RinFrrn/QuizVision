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
        val body = JsonObject().apply {
            addProperty("model", config.model.trim())
            addProperty("temperature", 0.3)
            addProperty("stream", false)
            addProperty("max_tokens", 1000)
            add("messages", JsonArray().apply {
                add(message("system", prompt.system))
                add(message("user", prompt.user))
            })
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return execute(request, ::parseCompletionResponse)
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

    private fun parseCompletionResponse(response: okhttp3.Response): String {
        val responseText = response.body?.string().orEmpty()
        ensureSuccessful(response, responseText)
        val content = runCatching {
            gson.fromJson(responseText, JsonObject::class.java)
                .getAsJsonArray("choices")
                ?.get(0)
                ?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")
                ?.asString
        }.getOrNull()?.trim().orEmpty()
        if (content.isBlank()) throw IOException("API 返回了空内容")
        return content
    }

    private fun parseModelsResponse(response: okhttp3.Response): List<String> {
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

    private fun ensureSuccessful(response: okhttp3.Response, responseText: String) {
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
        parser: (okhttp3.Response) -> T
    ): T {
        val result = suspendCancellableCoroutine<Result<T>> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
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

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
