package com.deepseek.rootagent

import java.io.DataOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Root Shell 执行器：通过 `su` 申请 root，在同一会话里顺序执行命令。
 * 每条命令用唯一 MARK 包裹，便于解析退出码。
 *
 * 读取方式：用 available()/read(char[]) 非阻塞地读，累积到 StringBuilder，
 * 再在其中查找 MARK，避免 BufferedReader.readLine() 在大输出/缓冲不 flush 时阻塞。
 */
object RootShell {
    @Volatile
    private var suProcess: Process? = null
    private var suInput: java.io.InputStream? = null
    private var suIn: DataOutputStream? = null
    private var appCtx: android.content.Context? = null

    fun init(ctx: android.content.Context) { appCtx = ctx.applicationContext }

    private fun isScreenCapture(cmd: String): Boolean {
        val c = cmd.lowercase()
        return c.contains("screencap") || c.contains("screenrecord") || c.contains("screenshot")
    }

    private fun hideFloat() {
        appCtx?.let {
            try { it.startService(android.content.Intent(it, FloatingService::class.java).setAction("hide")) } catch (_: Exception) {}
        }
    }
    private fun showFloat() {
        appCtx?.let {
            try { it.startService(android.content.Intent(it, FloatingService::class.java).setAction("show")) } catch (_: Exception) {}
        }
    }

    fun hasRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            p.exitValue() == 0 && out.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /** 确保 su 会话已建立 */
    @Synchronized
    private fun ensureSession() {
        if (suProcess?.isAlive == true) return
        val p = Runtime.getRuntime().exec("su")
        suProcess = p
        suInput = p.inputStream
        suIn = DataOutputStream(p.outputStream)
        Thread {
            try {
                p.errorStream.copyTo(OutputStream.nullOutputStream())
            } catch (_: Exception) {
            }
        }.start()
    }

    data class Result(
        val code: Int,
        val out: String,
        val err: String,
        val durationMs: Long
    ) {
        fun combined(): String {
            val sb = StringBuilder()
            if (out.isNotBlank()) sb.append(out)
            if (err.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n")
                sb.append("[stderr]\n").append(err)
            }
            if (sb.isEmpty()) sb.append("(no output)")
            return sb.toString().trim()
        }
    }

    /**
     * 执行一条 root 命令。
     */
    fun exec(cmd: String, timeoutSec: Int = 60): Result {
        ensureSession()
        val willCapture = isScreenCapture(cmd)
        if (willCapture) { hideFloat(); try { Thread.sleep(120) } catch (_: Exception) {} }
        val start = System.currentTimeMillis()
        val mark = "DSA_MARK_${System.nanoTime()}_${(Math.random() * 100000).toInt()}"
        LogManager.log("\$ su -c $cmd")
        return try {
            val inStream = suInput!!
            val outStream = suIn!!

            // 发送命令：(cmd) 2>&1; echo "MARK:$?"
            outStream.writeBytes("($cmd) 2>&1; echo \"$mark:\$?\"\n")
            outStream.flush()

            val sb = StringBuilder()
            val deadline = start + timeoutSec * 1000L
            var code = -1

            while (System.currentTimeMillis() < deadline) {
                val avail = inStream.available()
                if (avail > 0) {
                    val buf = ByteArray(avail)
                    val n = inStream.read(buf)
                    if (n > 0) sb.append(String(buf, 0, n, Charsets.UTF_8))
                    // 检查 MARK 是否已到达
                    val idx = sb.indexOf(mark)
                    if (idx >= 0) {
                        val after = sb.substring(idx + mark.length).trim()
                        code = after.removePrefix(":").takeIf { it.isNotEmpty() }?.toIntOrNull() ?: -1
                        val output = sb.substring(0, idx).trim()
                        val dur = System.currentTimeMillis() - start
                        LogManager.log("  -> exit=$code, ${output.length} chars, ${dur}ms")
                        if (willCapture) showFloat()
                        return Result(code, output, "", dur)
                    }
                } else {
                    Thread.sleep(30)
                }
            }
            val dur = System.currentTimeMillis() - start
            LogManager.log("  !! TIMEOUT after ${timeoutSec}s, partial=${sb.length} chars")
            if (willCapture) showFloat()
            synchronized(this) {
                try { suProcess?.destroy() } catch (_: Exception) {}
                suProcess = null
            }
            Result(-1, sb.toString().trim(), "timeout after ${timeoutSec}s", dur)
        } catch (e: Exception) {
            LogManager.log("  !! exec error: ${e.message}")
            synchronized(this) {
                try { suProcess?.destroy() } catch (_: Exception) {}
                suProcess = null
            }
            if (willCapture) showFloat()
            Result(-1, "", e.message ?: "exec error", System.currentTimeMillis() - start)
        }
    }

    /** 中断当前正在跑的命令（向 su 会话发 Ctrl-C） */
    fun interrupt() {
        try {
            suIn?.writeBytes("\u0003")
            suIn?.flush()
            LogManager.log("  !! sent Ctrl-C to su session")
        } catch (e: Exception) {
            LogManager.log("  !! interrupt failed: ${e.message}")
        }
    }

    /** 普通（非 root）命令 */
    fun execNoRoot(cmd: Array<String>, timeoutSec: Int = 15): Result {
        val start = System.currentTimeMillis()
        return try {
            val p = ProcessBuilder(*cmd).start()
            val ok = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            if (!ok) p.destroyForcibly()
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            Result(if (ok) p.exitValue() else -1, out, err, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            Result(-1, "", e.message ?: "error", System.currentTimeMillis() - start)
        }
    }
}
