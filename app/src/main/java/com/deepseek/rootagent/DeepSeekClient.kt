package com.deepseek.rootagent

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * DeepSeek Chat Completions 客户端（OpenAI 兼容协议），支持文本 + 多模态（图片）。
 */
class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String
) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    data class FunctionCall(
        val name: String,
        val arguments: String
    )

    data class ToolCall(
        val id: String,
        @SerializedName("type") val valType: String = "function",
        val function: FunctionCall
    ) {
        val name: String get() = function.name
        val arguments: String get() = function.arguments
    }

    /** 多模态 content part */
    data class ContentPart(
        val type: String,
        val text: String? = null,
        val image_url: ImageUrl? = null
    )

    data class ImageUrl(val url: String)

    data class Message(
        val role: String,
        val content: String? = null,
        val contentParts: List<ContentPart>? = null,
        val tool_calls: List<ToolCall>? = null,
        val tool_call_id: String? = null,
        val name: String? = null
    )

    data class ChoiceMessage(
        val role: String,
        val content: String?,
        val tool_calls: List<ToolCall>?
    )

    data class Response(
        val content: String?,
        val toolCalls: List<ToolCall>?,
        val rawMessage: ChoiceMessage
    )

    private fun messageToJson(m: Message): JsonObject {
        val jo = JsonObject()
        jo.addProperty("role", m.role)
        if (m.contentParts != null) {
            // 多模态：content 是数组
            val arr = JsonArray()
            m.contentParts.forEach { part ->
                val p = JsonObject()
                p.addProperty("type", part.type)
                when (part.type) {
                    "text" -> p.addProperty("text", part.text ?: "")
                    "image_url" -> {
                        val iu = JsonObject()
                        iu.addProperty("url", part.image_url?.url ?: "")
                        p.add("image_url", iu)
                    }
                }
                arr.add(p)
            }
            jo.add("content", arr)
        } else {
            jo.addProperty("content", m.content)
        }
        m.tool_calls?.let { tc -> jo.add("tool_calls", gson.toJsonTree(tc)) }
        m.tool_call_id?.let { jo.addProperty("tool_call_id", it) }
        m.name?.let { jo.addProperty("name", it) }
        return jo
    }

    fun chat(
        messages: List<Message>,
        toolsJson: String?
    ): Response {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val root = JsonObject()
        root.addProperty("model", model)
        val msgsArr = JsonArray()
        messages.forEach { msgsArr.add(messageToJson(it)) }
        root.add("messages", msgsArr)
        if (!toolsJson.isNullOrBlank()) {
            root.add("tools", com.google.gson.JsonParser.parseString(toolsJson).asJsonArray)
            root.addProperty("tool_choice", "auto")
        }
        root.addProperty("temperature", 0.2)

        val body = root.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        LogManager.log("[api] POST $url model=$model msgs=${messages.size}")
        // 网络错误自动重试 2 次
        var lastErr: Exception? = null
        repeat(3) { attempt ->
            try {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        LogManager.log("[api] HTTP ${resp.code}: ${text.take(800)}")
                        throw RuntimeException("HTTP ${resp.code}: ${text.take(500)}")
                    }
                    val jo = gson.fromJson(text, JsonObject::class.java)
                    val choice = jo.getAsJsonArray("choices").get(0).asJsonObject
                    val msg = choice.getAsJsonObject("message")
                    val content = msg.get("content")?.takeIf { !it.isJsonNull }?.asString
                    val toolCalls = msg.getAsJsonArray("tool_calls")?.map { tcEl ->
                        val tc = tcEl.asJsonObject
                        val func = tc.getAsJsonObject("function")
                        ToolCall(
                            id = tc.get("id").asString,
                            function = FunctionCall(
                                name = func.get("name").asString,
                                arguments = func.get("arguments").asString
                            )
                        )
                    }
                    LogManager.log("[api] reply: content=${content?.length ?: 0} chars, tool_calls=${toolCalls?.size ?: 0}")
                    return Response(
                        content = content,
                        toolCalls = toolCalls,
                        rawMessage = ChoiceMessage(
                            role = "assistant",
                            content = content,
                            tool_calls = toolCalls
                        )
                    )
                }
            } catch (e: java.net.UnknownHostException) {
                lastErr = e
                LogManager.log("[api] DNS resolve failed (attempt ${attempt+1}/3): ${e.message}")
                Thread.sleep(1500)
            } catch (e: java.io.IOException) {
                lastErr = e
                LogManager.log("[api] IO error (attempt ${attempt+1}/3): ${e.message}")
                Thread.sleep(1500)
            }
        }
        throw lastErr ?: RuntimeException("API call failed")
    }
}
