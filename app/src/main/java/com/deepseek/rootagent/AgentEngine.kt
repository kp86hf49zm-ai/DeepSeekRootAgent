package com.deepseek.rootagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Agent 主循环：用户任务 -> DeepSeek -> tool_calls -> 执行 -> 回灌 -> ... -> finish。
 * 会话历史跨任务保留并持久化到磁盘，App 重启后自动恢复。
 */
class AgentEngine(
    private val ctx: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onUser(text: String)
        fun onAssistant(text: String)
        fun onToolCall(display: String)
        fun onToolResult(result: String)
        fun onError(msg: String)
        fun onDone(summary: String)
        /** 任务运行状态变化（true=开始跑，false=结束），UI 用来切发送/停止按钮 */
        fun onRunningChanged(running: Boolean) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val gson = Gson()

    @Volatile
    private var stopped = false

    @Volatile
    var running = false
        private set

    private val history = mutableListOf<DeepSeekClient.Message>()

    private val maxHistoryMessages = 60

    /** 项目管理：每个项目一个独立会话文件 */
    data class Project(val id: String, val name: String, val createdAt: Long)

    private val projectsDir: File by lazy { File(ctx.filesDir, "projects").apply { mkdirs() } }
    private val indexFile: File by lazy { File(ctx.filesDir, "projects_index.json") }

    var currentProjectId: String = ""
        private set
    var currentProjectName: String = ""
        private set

    private fun projectFile(id: String) = File(projectsDir, "$id.json")

    fun listProjects(): List<Project> {
        return try {
            if (!indexFile.exists()) return emptyList()
            val arr = JsonParser.parseString(indexFile.readText()).asJsonArray
            arr.map { gson.fromJson(it, Project::class.java) }.sortedByDescending { it.createdAt }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveProjectList(list: List<Project>) {
        indexFile.writeText(gson.toJson(list))
    }

    /** 切换到指定项目（或新建）。调用后会重新加载历史并触发 onProjectLoaded 回调。 */
    fun switchProject(id: String, name: String): Boolean {
        if (running) return false
        synchronized(history) { history.clear() }
        currentProjectId = id
        currentProjectName = name
        Prefs.set(ctx, "current_project_id", id)
        Prefs.set(ctx, "current_project_name", name)
        if (!loadHistory()) {
            clearHistory()
        }
        return true
    }

    fun createProject(name: String): Project {
        val id = "p_${System.currentTimeMillis()}"
        val p = Project(id, name, System.currentTimeMillis())
        val list = (listProjects() + p).toMutableList()
        saveProjectList(list)
        return p
    }

    fun deleteProject(id: String) {
        projectFile(id).delete()
        saveProjectList(listProjects().filter { it.id != id })
    }

    private val historyFile: File
        get() = if (currentProjectId.isBlank()) File(ctx.filesDir, "conversation.json")
                else projectFile(currentProjectId)

    fun stop() {
        stopped = true
        RootShell.interrupt()
    }

    private val systemPrompt = """
你是一个运行在已 Root 的 Android 手机上的 Agent。你的每一步动作都会真实地在用户手机上执行。
你会记住当前会话里用户之前说过的话、之前执行过的命令和结果；当用户跟进追问时，基于这些上下文回答，不要让用户重复说一遍。

工作准则：
1. 把用户的自然语言任务拆解为最小可执行步骤，优先调用工具，不要空谈。
2. 调用 shell_exec 时命令要具体、幂等，避免危险操作（不要 rm -rf /、不要 reboot、不要清空 /data）。
3. 每一步工具执行后，根据输出判断下一步；不要凭空假设结果。
4. 完成后调用 finish 工具给出总结。
5. 如果需要用户确认高风险操作（卸载系统应用、修改关键系统设置），先在回复里说明风险，再继续。
6. 单次任务循环步数有限，尽量高效。
7. 如果用户只是聊天/提问而不需要执行任何系统操作，直接回答即可，不必强行调用工具。
8. 不要用 `find /`、`find /system -name ...` 这种全盘扫描命令，会跑很久。找文件优先用已知具体路径（/system/bin、/system/framework、/data/local/tmp、/system/app 等），或用 `which <cmd>`、`ls <已知目录>`。
9. 每条 shell 命令最长 90 秒超时，不要跑长循环或大文件下载。

当前你可以使用的工具已在 tools 参数中给出。
【铁律】需要在输入框打字时，唯一方式是调用 input_text 工具（支持中英文）。绝对禁止用 shell_exec 跑 input text、input keyevent 打字、打拼音找联想词等任何方式。input_text 一定能用，直接调用，不要先试别的。不要主动发表情包、表情符号，除非用户明确要求。
""".trimIndent()

    init {
        // 启动时恢复上次打开的项目；如果没有项目列表，自动建一个默认项目
        val savedId = Prefs.get(ctx, "current_project_id", "")
        val savedName = Prefs.get(ctx, "current_project_name", "")
        var projects = listProjects()
        if (projects.isEmpty()) {
            val p = createProject("默认项目")
            projects = listOf(p)
            currentProjectId = p.id
            currentProjectName = p.name
        } else if (savedId.isNotBlank() && projects.any { it.id == savedId }) {
            currentProjectId = savedId
            currentProjectName = savedName.ifBlank { projects.first { it.id == savedId }.name }
        } else {
            currentProjectId = projects[0].id
            currentProjectName = projects[0].name
        }
        if (!loadHistory()) {
            clearHistory()
        } else {
            LogManager.log("==== conversation restored: ${history.size} messages ====")
        }
    }

    data class UiMessage(val role: String, val text: String)

    /** 给 UI 渲染用的历史快照（跳过 system，tool_calls 折叠成简短描述） */
    fun snapshotForUi(): List<UiMessage> {
        return synchronized(history) {
            history.filter { it.role != "system" }.map { m ->
                val text = when (m.role) {
                    "user" -> m.content ?: ""
                    "assistant" -> buildString {
                        if (!m.content.isNullOrBlank()) append(m.content)
                        m.tool_calls?.forEach { tc ->
                            if (isNotEmpty()) append("\n")
                            append("🔧 ").append(tc.function.name)
                        }
                    }
                    "tool" -> m.content ?: ""
                    else -> ""
                }
                UiMessage(m.role, text)
            }.filter { it.text.isNotBlank() }
        }
    }

    private fun loadHistory(): Boolean {
        return try {
            if (!historyFile.exists()) return false
            val text = historyFile.readText()
            if (text.isBlank()) return false
            val arr = JsonParser.parseString(text).asJsonArray
            val list = gson.fromJson(arr, Array<DeepSeekClient.Message>::class.java).toMutableList()
            if (list.isEmpty() || list[0].role != "system") return false

            // 从后往前清理不完整的 tool_calls 配对：
            // 遇到 assistant(tool_calls=N 个)，检查它后面是否跟着 N 个 role=tool 消息，
            // 数量不对就把这个 assistant 以及它后面那堆孤儿 tool 全丢掉，再继续往前。
            var i = list.size - 1
            while (i >= 0) {
                val m = list[i]
                if (m.role == "assistant" && !m.tool_calls.isNullOrEmpty()) {
                    val need = m.tool_calls!!.size
                    var have = 0
                    var j = i + 1
                    while (j < list.size && list[j].role == "tool") { have++; j++ }
                    if (have < need) {
                        // 从 i 到 j-1 整段删掉（这个 assistant + 不够数的 tool）
                        LogManager.log("loadHistory: dropping incomplete tool_calls pair at idx $i (need=$need have=$have)")
                        for (k in j - 1 downTo i) list.removeAt(k)
                        i = j - 1
                    }
                }
                i--
            }

            // 再清一遍末尾残留
            while (list.isNotEmpty() && list.last().role == "tool") {
                LogManager.log("dropping orphan tool message at end")
                list.removeAt(list.size - 1)
            }
            while (list.isNotEmpty() && list.last().role == "assistant" && list.last().tool_calls != null) {
                LogManager.log("dropping assistant with unresolved tool_calls")
                list.removeAt(list.size - 1)
            }
            history.clear()
            history.addAll(list)
            true
        } catch (e: Exception) {
            LogManager.log("loadHistory failed: ${e.message}")
            false
        }
    }

    private fun saveHistory() {
        val snapshot = synchronized(history) { history.toList() }
        Thread {
            try {
                historyFile.writeText(gson.toJson(snapshot))
            } catch (e: Exception) {
                LogManager.log("saveHistory failed: ${e.message}")
            }
        }.start()
    }

    fun clearHistory() {
        synchronized(history) {
            history.clear()
            history.add(DeepSeekClient.Message(role = "system", content = systemPrompt))
        }
        try { historyFile.delete() } catch (_: Exception) {}
        LogManager.log("==== conversation cleared ====")
    }

    private fun trimHistory() {
        synchronized(history) {
            // 彻底清理不配对的 tool_calls：从后往前扫描
            var i = history.size - 1
            while (i >= 0) {
                val m = history[i]
                if (m.role == "assistant" && !m.tool_calls.isNullOrEmpty()) {
                    val need = m.tool_calls!!.size
                    var have = 0
                    var j = i + 1
                    while (j < history.size && history[j].role == "tool") { have++; j++ }
                    if (have < need) {
                        LogManager.log("trimHistory: dropping incomplete tool_calls at idx $i (need=$need have=$have)")
                        for (k in j - 1 downTo i) history.removeAt(k)
                        i = j - 1
                    }
                }
                i--
            }
            while (history.isNotEmpty() && history.last().role == "tool") history.removeAt(history.size - 1)
            while (history.isNotEmpty() && history.last().role == "assistant" && history.last().tool_calls != null) history.removeAt(history.size - 1)

            if (history.size <= maxHistoryMessages) return
            val sys = history[0]
            val keepFrom = history.size - maxHistoryMessages + 1
            var start = keepFrom
            // 裁剪起点不能落在 tool 响应上（它前面的 assistant tool_calls 已被裁掉），往后退到非 tool
            while (start < history.size && history[start].role == "tool") start++
            val trimmed = history.subList(start, history.size).toMutableList()
            history.clear()
            history.add(sys)
            history.addAll(trimmed)
            LogManager.log("history trimmed to ${history.size} messages")
        }
    }

    /** 读截图文件，缩放到宽 720，转 JPEG 质量 60，返回 base64（不带 data: 前缀） */
    private fun encodeScreenshot(path: String): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val srcW = opts.outWidth
        val srcH = opts.outHeight
        val targetW = 720
        val sample = (srcW.toFloat() / targetW).toInt().coerceAtLeast(1)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(path, decodeOpts)
            ?: throw RuntimeException("decode screenshot failed")
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 60, baos)
        val bytes = baos.toByteArray()
        LogManager.log("screenshot encoded: ${bmp.width}x${bmp.height}, ${bytes.size / 1024}KB")
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun run(userTask: String, imagePath: String? = null) {
        if (running) {
            main.post { callback.onError("上一个任务还在执行中，请等它结束或先停止。") }
            return
        }
        if (stopped) { stopped = false }
        running = true
        main.post { callback.onRunningChanged(true) }
        callback.onUser(userTask)
        Thread {
            try {
                val apiKey = Prefs.get(ctx, Prefs.KEY_API_KEY, "")
                val baseUrl = Prefs.get(ctx, Prefs.KEY_BASE_URL, "https://api.deepseek.com")
                val model = Prefs.get(ctx, Prefs.KEY_MODEL, "deepseek-chat")
                val timeout = Prefs.getInt(ctx, Prefs.KEY_TIMEOUT, 60)
                val maxSteps = Prefs.getInt(ctx, Prefs.KEY_MAX_STEPS, 15)

                if (apiKey.isBlank()) {
                    main.post { callback.onError("未配置 API Key，请先到「设置」填写。") }
                    return@Thread
                }

                val textClient = DeepSeekClient(apiKey, baseUrl, model)
                val visionClient = DeepSeekClient(apiKey, baseUrl, model)

                synchronized(history) {
                    val env = Prefs.get(ctx, "env_mode", "android")
                    val envHint = when (env) {
                        "linux" -> "[环境：用户已选择 Alpine Linux 环境。请优先用 linux_exec 工具完成任务，除非任务明确需要操作 Android 系统（如截图、点击、卸载 app）。]\n\n"
                        else -> ""
                    }
                    if (imagePath != null) {
                        val imgBytes = java.io.File(imagePath).readBytes()
                        val b64 = android.util.Base64.encodeToString(imgBytes, android.util.Base64.NO_WRAP)
                        val ext = imagePath.substringAfterLast(".", "jpg").lowercase()
                        val mime = if (ext == "png") "image/png" else if (ext == "webp") "image/webp" else "image/jpeg"
                        history.add(DeepSeekClient.Message(role = "user", contentParts = listOf(
                            DeepSeekClient.ContentPart("text", text = envHint + userTask),
                            DeepSeekClient.ContentPart("image_url", image_url = DeepSeekClient.ImageUrl("data:$mime;base64,$b64"))
                        )))
                    } else {
                        history.add(DeepSeekClient.Message(role = "user", content = envHint + userTask))
                    }
                }
                saveHistory()
                trimHistory()

                var finished = false
                var finalSummary = ""

                for (step in 1..maxSteps) {
                    if (stopped) {
                        main.post { callback.onError("已手动停止") }
                        return@Thread
                    }
                    LogManager.log("==== agent step $step/$maxSteps ====")
                    val snapshot = synchronized(history) { history.toList() }
                    val lastHasImage = snapshot.lastOrNull()?.contentParts?.any { it.type == "image_url" } == true
                    val activeClient = if (lastHasImage) visionClient else textClient
                    val resp = activeClient.chat(snapshot, ToolExecutor.TOOLS_JSON)

                    synchronized(history) {
                        history.add(
                            DeepSeekClient.Message(
                                role = "assistant",
                                content = resp.content,
                                tool_calls = resp.toolCalls
                            )
                        )
                    }
                    saveHistory()

                    if (!resp.content.isNullOrBlank()) {
                        main.post { callback.onAssistant(resp.content) }
                    }

                    val calls = resp.toolCalls
                    if (calls.isNullOrEmpty()) {
                        finished = true
                        finalSummary = resp.content ?: "任务结束"
                        break
                    }

                    for (tc in calls) {
                        if (stopped) {
                            main.post { callback.onError("已手动停止") }
                            return@Thread
                        }
                        val preview = when (tc.name) {
                            "shell_exec" -> runCatching {
                                com.google.gson.JsonParser.parseString(tc.arguments).asJsonObject
                                    .get("command")?.asString ?: tc.arguments
                            }.getOrDefault(tc.arguments).let { "🔧 ${it.take(200)}" }
                            else -> "🔧 调用 ${tc.name}"
                        }
                        main.post { callback.onToolCall(preview) }
                        val tr = ToolExecutor.execute(ctx, tc.name, tc.arguments, timeout)
                        main.post { callback.onToolResult(tr.feedback.take(1500)) }

                        synchronized(history) {
                            history.add(
                                DeepSeekClient.Message(
                                    role = "tool",
                                    tool_call_id = tc.id,
                                    name = tc.name,
                                    content = tr.feedback
                                )
                            )
                        }
                        // 如果这是截图工具，把图片压缩成 base64 作为多模态 user 消息追加
                        tr.screenshotPath?.let { path ->
                            runCatching {
                                val b64 = encodeScreenshot(path)
                                synchronized(history) {
                                    history.add(
                                        DeepSeekClient.Message(
                                            role = "user",
                                            contentParts = listOf(
                                                DeepSeekClient.ContentPart(
                                                    type = "text",
                                                    text = "这是刚才截取的当前屏幕截图（已压缩为 JPEG）。请观察屏幕内容，判断下一步该点哪里或做什么，然后调用相应工具。"
                                                ),
                                                DeepSeekClient.ContentPart(
                                                    type = "image_url",
                                                    image_url = DeepSeekClient.ImageUrl(url = "data:image/jpeg;base64,$b64")
                                                )
                                            )
                                        )
                                    )
                                }
                                main.post { callback.onToolCall("🖼️ 截图已发送给视觉模型") }
                            }.onFailure {
                                LogManager.log("encodeScreenshot failed: ${it.message}")
                            }
                        }
                        saveHistory()

                        if (tr.isFinish) {
                            finished = true
                            finalSummary = tr.finishSummary ?: tr.feedback
                            break
                        }
                    }
                    if (finished) break
                }

                if (!finished) {
                    finalSummary = "已达到最大步数（$maxSteps），任务可能未完成。请扩大步数或拆分任务。"
                    main.post { callback.onAssistant(finalSummary) }
                }

                val sum = finalSummary
                main.post { callback.onDone(sum) }
            } catch (e: Exception) {
                LogManager.log("agent error: ${e.message}")
                main.post { callback.onError("执行出错: ${e.message}") }
            } finally {
                running = false
                main.post { callback.onRunningChanged(false) }
                saveHistory()
            }
        }.start()
    }
}
