# DeepSeek Root Agent

运行在已 Root Android 手机上的 AI Agent 工具。用自然语言下达任务，LLM 通过 Function Calling 自主拆解步骤、调用 Root Shell 在手机上真实执行（卸载应用、改系统设置、截图、模拟触控、打字、跑 Linux 命令等）。

支持任意 OpenAI 兼容 API（DeepSeek / OpenAI / 通义千问 / Moonshot / OpenRouter 等），内置 Alpine Linux 环境，多项目会话管理，悬浮窗运行状态。

> ⚠️ 本应用需要 **Root 权限**，未检测到 Root 将无法进入。所有命令真实执行在你的设备上，请谨慎使用。

## 功能

- **自然语言 Agent**：输入一句话，AI 自动拆解为最小步骤并逐个执行
- **Function Calling 工具集**（均以 root 执行）：
  - `shell_exec` 执行任意 root shell 命令
  - `file_read` / `file_write` 读写任意路径文件
  - `app_list` / `app_uninstall` 应用管理（含系统应用）
  - `take_screenshot_and_observe` 截图并回传视觉闭环（AI 能看到屏幕再操作）
  - `input_tap` / `input_swipe` 模拟点击 / 滑动
  - `input_text` 在任意输入框打字（中英文，透明 Activity 前台写剪贴板方案）
  - `settings_put` 修改系统设置
  - `device_info` 设备信息
  - `finish` 任务收尾
- **截屏视觉闭环**：AI 截图后分析界面，基于坐标点击操作
- **内置 Alpine Linux**：chroot 进内置 Linux 环境执行命令（独立于 Android）
- **多项目会话**：每个项目独立持久化对话历史（`filesDir/projects/*.json`），支持新建 / 切换 / 清除记忆
- **悬浮窗**：任务运行时切到其他 App 显示 AI 状态与停止按钮；`setSkipCapture` 防截图捕获
- **多服务商接入**：DeepSeek / OpenAI / 通义千问 / Moonshot / OpenRouter 一键切换，自动填充 Base URL 与模型
- **日志系统**：设备端日志 + 应用内查看 + 一键导出
- **固定签名**：持续签名一致，可直接覆盖安装

## 环境要求

| 项目 | 要求 |
|---|---|
| 设备 | 已 Root（Magisk / KernelSU / SuperSU），Android 8.0+（API 26） |
| 构建 | JDK 17+，Android SDK 35 |
| 依赖 | 一个 OpenAI 兼容 API 的 Key |

## 构建

```bash
# 克隆后直接用 wrapper 构建
./gradlew assembleRelease

# 产物
# app/build/outputs/apk/release/app-release.apk
```

> 签名：项目默认引用 `keystore/deepseek-agent.jks` 固定签名（口令 `deepseek2026`，alias `deepseek-agent`）。该 keystore 为开发者私有、**不包含在开源包中**。自行构建时可创建自己的 keystore 替换 `app/build.gradle.kts` 中的 `signingConfigs`，或移除 signingConfig 使用默认 debug 签名。

## 快速开始

1. 安装 APK 到已 Root 手机，首次启动授予 su 权限
2. 右上角菜单 → 设置，选择服务商（如 DeepSeek），填入 API Key，保存
3. 主界面输入自然语言任务，例如：
   - "列出所有第三方应用，然后卸载某某"
   - "把屏幕亮度调到最低，截一张图"
   - "打开备忘录，输入 '你好世界'"
   - "用内置 Linux 跑 `uname -a`"

## 目录结构

```
DeepSeekRootAgent/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/alpine-rootfs.tar.gz # 内置 Alpine aarch64 rootfs（构建时解压为 .tar）
│       ├── java/com/deepseek/rootagent/
│       │   ├── MainActivity.kt        # 聊天主界面
│       │   ├── SettingsActivity.kt    # 服务商/模型/超时配置
│       │   ├── LogViewerActivity.kt   # 日志查看
│       │   ├── AgentEngine.kt         # Agent 主循环（history + tool loop）
│       │   ├── DeepSeekClient.kt      # OpenAI 兼容 Chat Completions 客户端
│       │   ├── ToolExecutor.kt        # Function Calling 工具执行
│       │   ├── RootShell.kt           # 常驻 su 会话执行器（带超时重建）
│       │   ├── LinuxEnv.kt            # Alpine chroot 环境管理
│       │   ├── FloatingService.kt     # 悬浮窗
│       │   ├── ClipActivity.kt        # 透明 Activity：前台写剪贴板
│       │   ├── LogManager.kt          # 日志
│       │   ├── Prefs.kt               # SharedPreferences
│       │   └── ui/                    # 聊天列表 Adapter
│       └── res/                       # 布局/字符串/主题/图标
├── gradlew / gradle/                 # Gradle Wrapper
├── settings.gradle.kts
├── build.gradle.kts
└── README.md
```

## 工作原理

见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 版本历史

- v2.4.x：多服务商接入、透明 Activity 剪贴板方案、root 检测门禁、DSH 风格 UI
- v2.3.x：全局 UI 重构（纯白 DSH Desktop 风格）、清除记忆、悬浮窗后台显示
- v2.2.x：input_text 打字工具、坐标换算修复、悬浮窗重构
- v2.0.x：内置 Alpine Linux、RootShell 超时重建
- v1.0.x：基础 Agent 循环 + root 工具集

## 安全与免责

- 本应用持有 root 权限，能执行任意命令，请仅在自己的设备上使用
- API Key 仅保存在本机 SharedPreferences
- 高危操作（卸载系统应用、改关键设置）前 AI 会先说明风险
- 使用本软件产生的任何后果由使用者自行承担

## License

[MIT](LICENSE)
