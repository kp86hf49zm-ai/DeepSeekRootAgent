package com.deepseek.rootagent

import android.content.pm.PackageManager
import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

/**
 * Agent 工具集：把 LLM 的 function call 映射到 root shell / Android API。
 */
object ToolExecutor {
    private const val MAX_OUTPUT = 4000

    /** OpenAI 兼容 tools JSON */
    val TOOLS_JSON: String = """
[
  {
    "type": "function",
    "function": {
      "name": "shell_exec",
      "description": "以 root 身份执行一条 Android shell 命令（sh -c）。返回 stdout 和退出码。用于文件/进程/系统操作。注意这是 Android 本体的 shell，不是 Linux 环境。",
      "parameters": {
        "type": "object",
        "properties": {
          "command": {"type": "string", "description": "要执行的完整 shell 命令"},
          "timeout": {"type": "integer", "description": "超时秒数，默认 60"}
        },
        "required": ["command"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "linux_exec",
      "description": "在内置 Alpine Linux 环境中执行命令。这里是完整 Linux：可以用 apk add 装包、python3、node、bash、git、curl、gcc 等。工作目录是 /root。需要跑脚本、装软件、做开发任务时用这个，不要用 shell_exec。",
      "parameters": {
        "type": "object",
        "properties": {
          "command": {"type": "string", "description": "要执行的完整 shell 命令（在 Linux 里）"},
          "timeout": {"type": "integer", "description": "超时秒数，默认 60，最大 120"}
        },
        "required": ["command"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "file_read",
      "description": "以 root 读取手机上的文本文件内容，最多返回 4000 字符。",
      "parameters": {
        "type": "object",
        "properties": {
          "path": {"type": "string", "description": "文件绝对路径"},
          "max_bytes": {"type": "integer", "description": "最多读取字节数，默认 4000"}
        },
        "required": ["path"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "file_write",
      "description": "以 root 把内容写入手机文件（覆盖或追加）。",
      "parameters": {
        "type": "object",
        "properties": {
          "path": {"type": "string"},
          "content": {"type": "string"},
          "append": {"type": "boolean", "description": "true=追加，false=覆盖"}
        },
        "required": ["path", "content"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "app_list",
      "description": "列出手机上已安装的第三方应用包名。",
      "parameters": {"type": "object", "properties": {}}
    }
  },
  {
    "type": "function",
    "function": {
      "name": "app_uninstall",
      "description": "以 root 卸载（或对系统应用执行 pm uninstall --user 0）指定包名的应用。",
      "parameters": {
        "type": "object",
        "properties": {"package": {"type": "string"}},
        "required": ["package"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "take_screenshot_and_observe",
      "description": "截一张当前屏幕并让模型看到截图。需要观察屏幕内容、识别按钮文字、决定点击位置时调用。调用后模型会在下一轮看到这张截图。",
      "parameters": {"type": "object", "properties": {}}
    }
  },
  {
    "type": "function",
    "function": {
      "name": "input_tap",
      "description": "以 root 模拟点击屏幕坐标。",
      "parameters": {
        "type": "object",
        "properties": {
          "x": {"type": "integer"},
          "y": {"type": "integer"}
        },
        "required": ["x", "y"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "input_swipe",
      "description": "以 root 模拟滑动。",
      "parameters": {
        "type": "object",
        "properties": {
          "x1": {"type": "integer"}, "y1": {"type": "integer"},
          "x2": {"type": "integer"}, "y2": {"type": "integer"},
          "duration_ms": {"type": "integer", "description": "默认 300"}
        },
        "required": ["x1", "y1", "x2", "y2"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "input_text",
      "description": "在当前聚焦的输入框里输入文字。支持中文（自动走剪贴板粘贴）。",
      "parameters": {
        "type": "object",
        "properties": {
          "text": {"type": "string", "description": "要输入的文字内容"}
        },
        "required": ["text"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "settings_put",
      "description": "修改 Android 系统设置（settings put）。namespace 可为 system/global/secure。",
      "parameters": {
        "type": "object",
        "properties": {
          "namespace": {"type": "string"},
          "key": {"type": "string"},
          "value": {"type": "string"}
        },
        "required": ["namespace", "key", "value"]
      }
    }
  },
  {
    "type": "function",
    "function": {
      "name": "device_info",
      "description": "返回设备型号、Android 版本、SDK、品牌等基本信息。",
      "parameters": {"type": "object", "properties": {}}
    }
  },
  {
    "type": "function",
    "function": {
      "name": "finish",
      "description": "任务完成时调用，给出给用户看的总结。之后不再调用其它工具。",
      "parameters": {
        "type": "object",
        "properties": {"summary": {"type": "string"}},
        "required": ["summary"]
      }
    }
  }
]
""".trimIndent()

    private val gson = Gson()

    data class ToolResult(
        val display: String,
        val feedback: String,
        val isFinish: Boolean = false,
        val finishSummary: String? = null,
        /** 若非空，表示执行完后需要把这张截图作为多模态消息喂回模型 */
        val screenshotPath: String? = null
    )

    fun execute(ctx: android.content.Context, name: String, argsJson: String, timeoutSec: Int): ToolResult {
        LogManager.log("[tool] call $name args=${argsJson.take(500)}")
        val args = runCatching { gson.fromJson(argsJson, JsonObject::class.java) }.getOrNull()
            ?: return ToolResult("工具参数解析失败", "ERROR: invalid JSON arguments: $argsJson")

        return when (name) {
            "shell_exec" -> {
                val cmd = args.get("command")?.asString ?: ""
                val to = (args.get("timeout")?.asInt ?: timeoutSec).coerceIn(5, 90)
                val r = RootShell.exec(cmd, to)
                val out = r.combined().take(MAX_OUTPUT)
                ToolResult("\$ $cmd", "exit=${r.code}\n$out")
            }

            "linux_exec" -> {
                val cmd = args.get("command")?.asString ?: ""
                val to = (args.get("timeout")?.asInt ?: timeoutSec).coerceIn(5, 120)
                if (!LinuxEnv.ensure(ctx)) {
                    ToolResult("🐧 linux: $cmd", "ERROR: Linux 环境初始化失败")
                } else {
                    val r = LinuxEnv.exec(ctx, cmd, to)
                    val out = r.combined().take(MAX_OUTPUT)
                    ToolResult("🐧 linux$ $cmd", "exit=${r.code}\n$out")
                }
            }

            "file_read" -> {
                val path = args.get("path")?.asString ?: ""
                val maxB = args.get("max_bytes")?.asInt ?: 4000
                // 用 root 读，便于访问 /data 下文件
                val r = RootShell.exec("cat '$path' | head -c $maxB", 20)
                ToolResult("📄 read $path", "exit=${r.code}\n${r.combined()}")
            }

            "file_write" -> {
                val path = args.get("path")?.asString ?: ""
                val content = args.get("content")?.asString ?: ""
                val append = args.get("append")?.asBoolean ?: false
                // 用 base64 避免转义问题
                val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
                val op = if (append) ">>" else ">"
                val r = RootShell.exec("echo $b64 | base64 -d $op '$path' && chmod 644 '$path'", 20)
                ToolResult("✏️ write $path (${content.length} chars)", "exit=${r.code} ${r.combined()}")
            }

            "app_list" -> {
                val pm = ctx.packageManager
                val pkgs = pm.getInstalledPackages(0)
                    .filter { (it.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                    .map { it.packageName }
                    .sorted()
                val text = pkgs.joinToString("\n")
                ToolResult("📱 列出 ${pkgs.size} 个第三方应用", text.take(MAX_OUTPUT))
            }

            "app_uninstall" -> {
                val pkg = args.get("package")?.asString ?: ""
                // 先尝试普通卸载，失败再用 root pm uninstall
                val r = RootShell.exec("pm uninstall --user 0 $pkg", 30)
                ToolResult("🗑️ uninstall $pkg", "exit=${r.code}\n${r.combined()}")
            }

            "take_screenshot_and_observe" -> {
                val tmpFile = File(ctx.cacheDir, "screen_${System.currentTimeMillis()}.png")
                val r = RootShell.exec("screencap -p ${tmpFile.absolutePath} && chmod 644 ${tmpFile.absolutePath}", 15)
                ToolResult(
                    display = "📸 截图已保存",
                    feedback = "截图已保存。图片宽720，实际屏幕宽1080，input_tap坐标需乘以1.5：实际x = 图中x * 1.5，实际y = 图中y * 1.5。",
                    screenshotPath = tmpFile.absolutePath
                )
            }

            "input_tap" -> {
                val x = args.get("x")?.asInt ?: 0
                val y = args.get("y")?.asInt ?: 0
                val r = RootShell.exec("input tap $x $y", 10)
                ToolResult("👆 tap ($x,$y)", "exit=${r.code}")
            }

            "input_swipe" -> {
                val x1 = args.get("x1")?.asInt ?: 0
                val y1 = args.get("y1")?.asInt ?: 0
                val x2 = args.get("x2")?.asInt ?: 0
                val y2 = args.get("y2")?.asInt ?: 0
                val d = args.get("duration_ms")?.asInt ?: 300
                val r = RootShell.exec("input swipe $x1 $y1 $x2 $y2 $d", 15)
                ToolResult("👆 swipe ($x1,$y1)->($x2,$y2) ${d}ms", "exit=${r.code}")
            }

            "input_text" -> {
                val text = args.get("text")?.asString ?: ""
                val tmpFile = java.io.File(ctx.cacheDir, "input_clip.txt")
                tmpFile.writeText(text)
                RootShell.exec("service call clipboard 2 s16 android s16 " + "\"$(cat ${tmpFile.absolutePath})\"", 10)
                Thread.sleep(300)
                RootShell.exec("input keyevent KEYCODE_PASTE", 10)
                ToolResult("⌨️ 输入: ${text.take(50)}", "done")
            }
            "settings_put" -> {
                val ns = args.get("namespace")?.asString ?: "system"
                val key = args.get("key")?.asString ?: ""
                val value = args.get("value")?.asString ?: ""
                val r = RootShell.exec("settings put $ns $key '$value'", 10)
                ToolResult("⚙️ settings put $ns $key=$value", "exit=${r.code}\n${r.combined()}")
            }

            "device_info" -> {
                val sb = StringBuilder()
                sb.append("Brand: ").append(Build.BRAND).append("\n")
                sb.append("Model: ").append(Build.MODEL).append("\n")
                sb.append("Device: ").append(Build.DEVICE).append("\n")
                sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
                sb.append("Product: ").append(Build.PRODUCT).append("\n")
                val su = RootShell.hasRoot()
                sb.append("Root: ").append(if (su) "granted" else "NOT granted")
                ToolResult("ℹ️ device info", sb.toString())
            }

            "finish" -> {
                val summary = args.get("summary")?.asString ?: ""
                ToolResult("✅ 任务完成", summary, isFinish = true, finishSummary = summary)
            }

            else -> ToolResult("未知工具: $name", "ERROR: unknown tool '$name'")
        }
    }
}
