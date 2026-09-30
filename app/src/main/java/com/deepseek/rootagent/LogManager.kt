package com.deepseek.rootagent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志管理器：同时写 logcat 风格文件与内存缓冲。
 * 日志文件落在 app 外部私有目录（/sdcard/Android/data/<pkg>/files/logs/），
 * 并提供 exportToDownload() 通过 root 拷贝到 /sdcard/Download/DeepSeekRootAgent/。
 */
object LogManager {
    private const val TAG = "DeepSeekAgent"
    private lateinit var logDir: File
    private lateinit var currentLog: File
    private val buf = StringBuilder()
    private val tsFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        logDir = File(ctx.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val name = "agent_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".log"
        currentLog = File(logDir, name)
        currentLog.createNewFile()
        log("==== DeepSeek Root Agent log started ====")
    }

    @Synchronized
    fun log(msg: String) {
        val line = "[${tsFmt.format(Date())}] $msg\n"
        android.util.Log.i(TAG, msg)
        try {
            currentLog.appendText(line)
        } catch (_: Exception) {
        }
        // 内存缓冲最多保留 200KB
        synchronized(buf) {
            buf.append(line)
            if (buf.length > 200_000) {
                buf.delete(0, buf.length - 100_000)
            }
        }
    }

    fun dump(): String = synchronized(buf) { buf.toString() }

    fun currentLogFile(): File = currentLog

    /** 通过 root 把日志目录拷贝到 /sdcard/Download/DeepSeekRootAgent/ */
    fun exportToDownload(): String {
        return try {
            val dest = "/sdcard/Download/DeepSeekRootAgent"
            RootShell.exec("mkdir -p $dest && cp -f ${currentLog.absolutePath} $dest/ && chmod 644 $dest/*.log", 15)
            "已导出到 /sdcard/Download/DeepSeekRootAgent/\n" +
                    "文件: ${currentLog.name}"
        } catch (e: Exception) {
            "导出失败: ${e.message}"
        }
    }
}
