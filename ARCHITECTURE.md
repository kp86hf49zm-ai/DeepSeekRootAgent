# 架构说明

DeepSeek Root Agent 是一个运行在已 Root Android 设备上的 LLM Agent：用户用自然语言下达任务，模型通过 Function Calling 自主决定调用哪些工具、按什么顺序执行，直到任务完成。

## 核心循环（AgentEngine）

```
用户输入
   │
   ▼
┌─────────────────────────────────────────────┐
│ Agent 循环（最多 N 步，默认 15）               │
│                                             │
│  history (含 system prompt + 历史消息)        │
│      │                                      │
│      ▼                                      │
│  POST /chat/completions (OpenAI 兼容)        │
│      │                                      │
│      ├── 返回 content → 作为回复显示          │
│      ├── 返回 finish 工具调用 → 结束任务      │
│      └── 返回其他工具调用 ──► ToolExecutor    │
│                                  │          │
│                          执行真实操作         │
│               （su / 截图 / 输入 / 文件…）     │
│                                  │          │
│                    工具结果回灌 history       │
│                    （继续下一轮循环）          │
└─────────────────────────────────────────────┘
```

- 每轮把 `system + 用户 + assistant + tool` 消息完整送入模型，保证多步任务的上下文
- 会话按项目持久化（`filesDir/projects/<id>.json`），重启后恢复
- 工具结果实时回调 UI（合并显示、默认折叠一行、点击展开）

## 工具执行（ToolExecutor / RootShell）

所有工具均通过**常驻 su 会话**执行：

- `RootShell` 维护一个持久的 `su` 子进程，命令写入 stdin、从 stdout 读取结果，避免每次操作都拉起一个 su（快且省电）
- 每条命令有超时；超时后销毁旧 su 进程并重建会话，防止连环卡死
- 命令输出截断保护（避免超长输出撑爆上下文）

| 工具 | 说明 |
|---|---|
| `shell_exec` | 任意 root shell 命令（最长 90s） |
| `file_read` / `file_write` | 任意路径文件读写（base64 传输） |
| `app_list` / `app_uninstall` | 列出 / 卸载应用 |
| `take_screenshot_and_observe` | `screencap` 截图，缩放到宽 720 编码回传 |
| `input_tap` / `input_swipe` | `input` 模拟点击 / 滑动（坐标 = 截图坐标 × 1.5） |
| `input_text` | 输入框打字（见下） |
| `settings_put` | 修改系统设置 |
| `device_info` | 设备信息 |
| `finish` | 结束任务并总结 |

### 中文输入方案（input_text）

Android 12+ 限制后台应用写剪贴板，导致 shell 命令（`cmd clipboard`、`service call clipboard`、clipper）在 Android 15 上全部失效。最终方案：

1. 启动**透明 Activity**（`ClipActivity`，通过 root `am start` 绕过后台启动限制）
2. 在 Activity 前台用 `ClipboardManager` 写入文本（前台写不受限）
3. Activity 自动 finish，App 发 `KEYCODE_PASTE` 粘贴到目标输入框

### 截屏视觉闭环

```
screencap → 缩放到宽 720 → JPEG base64 → 作为 image_url 回传模型
模型"看到"屏幕 → 返回点击/滑动坐标（基于 720 宽）→ ×1.5 换算回真实坐标 → input tap
```

## 内置 Alpine Linux（LinuxEnv）

- `assets/alpine-rootfs.tar`（aarch64）首次启动解压到 `filesDir/alpine`
- 通过 `chroot` 进入，`mount` 绑定 /dev /proc /sys
- 环境变量隔离（避免 PATH 污染命中 Android 二进制）
- 可切换环境：右上角标题栏在 `Android` / `Linux` 之间切换，工具执行自动落入当前环境

## 悬浮窗（FloatingService）

- 仅当 AI 任务运行且 App 退到后台时显示（前台自动隐藏）
- 显示 AI 最近输出 + 红色停止按钮
- 反射调用 `WindowManager.LayoutParams.setSkipCapture(true)`：系统截图 / 录屏时该窗口不出现

## 多项目会话

- 每个项目独立 `conversation` 文件（`projects/<id>.json`）+ 索引 `projects_index.json`
- 新建 / 切换 / 清除记忆（右上角菜单）
- 各项目 history 互不影响

## 多服务商接入

- `SettingsActivity` 内置服务商预设：DeepSeek / OpenAI / 通义千问 / Moonshot / OpenRouter
- 选中服务商自动填充 Base URL 与模型列表
- `DeepSeekClient` 走标准 OpenAI 兼容 `/chat/completions`，支持多模态 content parts

## 日志（LogManager）

- 设备端：`/sdcard/Android/data/com.deepseek.rootagent/files/logs/agent_*.log`
- 应用内 LogViewer 实时查看
- 一键导出到 Download
