package com.deepseek.rootagent

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deepseek.rootagent.ui.ChatAdapter
import com.deepseek.rootagent.ui.ChatMessage
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: ChatAdapter
    private lateinit var input: EditText
    private lateinit var btnSend: android.widget.ImageView
    private lateinit var recycler: RecyclerView
    private lateinit var btnScrollBottom: android.widget.ImageView
    private var engine: AgentEngine? = null
    private var isRunning = false
    private var isAtBottom = true
    private var pendingImagePath: String? = null
    private var pendingToolCall: String? = null
    private var isAppInForeground = true
    private val PICK_IMAGE = 1001

    private fun updateFloatText(t: String) {
            try {
                val i = android.content.Intent(this, FloatingService::class.java).setAction("update").putExtra("text", t)
                startService(i)
            } catch (_: Exception) {}
        }

    private fun updateSubtitle() {
        val proj = engine?.currentProjectName ?: ""
        val env = Prefs.get(this, "env_mode", "android")
        val envTag = if (env == "linux") "🐧 Linux" else "📱 Android"
        supportActionBar?.subtitle = null; supportActionBar?.title = "deepseek"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        LogManager.init(this)

        // 检测 root，无 root 直接退出
        val rootCheck = RootShell.exec("id -u", 5)
        if (rootCheck.code != 0 || !rootCheck.out.contains("0")) {
            android.app.AlertDialog.Builder(this)
                .setTitle("需要 Root 权限")
                .setMessage("本应用必须以 Root 权限运行，请确认设备已 Root 并授权。")
                .setCancelable(false)
                .setPositiveButton("退出") { _, _ -> finish() }
                .show()
            return
        }
        RootShell.init(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        recycler = findViewById<RecyclerView>(R.id.recycler)
        adapter = ChatAdapter()
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        btnScrollBottom = findViewById(R.id.btnScrollBottom)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = adapter.itemCount
                isAtBottom = lastVisible >= total - 1
                btnScrollBottom.visibility = if (isAtBottom) android.view.View.GONE else android.view.View.VISIBLE
            }
        })
        btnScrollBottom.setOnClickListener { recycler.smoothScrollToPosition(adapter.itemCount - 1) }

        input = findViewById(R.id.input)
        btnSend = findViewById(R.id.btnSend)
        btnSend.setOnClickListener {
            if (isRunning) engine?.stop() else send()
        }

        findViewById<android.widget.ImageView>(R.id.btnAttach).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            startActivityForResult(intent, PICK_IMAGE)
        }

        // 环境切换：点标题栏
        updateSubtitle()
        toolbar.setOnClickListener {
            val cur = Prefs.get(this, "env_mode", "android")
            val next = if (cur == "linux") "android" else "linux"
            Prefs.set(this, "env_mode", next)
            updateSubtitle()
            Toast.makeText(this, if (next == "linux") "已切换到 Alpine Linux" else "已切换到 Android", Toast.LENGTH_SHORT).show()
        }

        val emptyState = findViewById<android.view.View>(R.id.emptyState)

        // 只创建一次 engine，保留跨任务会话记忆
        engine = AgentEngine(this, object : AgentEngine.Callback {
            override fun onUser(text: String) {
                adapter.add(ChatMessage(ChatMessage.Type.USER, text))
                emptyState.visibility = android.view.View.GONE
                if (isAtBottom) recycler.post { recycler.smoothScrollToPosition(adapter.itemCount - 1) }
            }

            override fun onAssistant(text: String) {
                if (text.isNotBlank()) {
                    adapter.add(ChatMessage(ChatMessage.Type.ASSISTANT, text))
                    emptyState.visibility = android.view.View.GONE
                    updateFloatText(text.take(200))
                    if (isAtBottom) recycler.post { recycler.smoothScrollToPosition(adapter.itemCount - 1) }
                }
            }

            override fun onToolCall(display: String) {
                pendingToolCall = display
            }

            override fun onToolResult(result: String) {
                val cmd = pendingToolCall ?: ""
                pendingToolCall = null
                val combined = if (cmd.isNotBlank()) cmd + "\n" + result else result
                adapter.appendTool(combined)
                updateFloatText(cmd.take(150))
                if (isAtBottom) recycler.post { recycler.smoothScrollToPosition(adapter.itemCount - 1) }
            }

            override fun onError(msg: String) {
                adapter.add(ChatMessage(ChatMessage.Type.ERROR, msg))
            }

            override fun onDone(summary: String) {
                adapter.add(ChatMessage(ChatMessage.Type.SYSTEM, "—— 任务结束 ——"))
            }

            override fun onRunningChanged(running: Boolean) {
                isRunning = running
                runOnUiThread {
                    if (running) {
                        btnSend.setImageResource(R.drawable.ic_stop)
                        btnSend.setBackgroundResource(R.drawable.bg_stop_button)
                        btnSend.contentDescription = "停止"
                        // 只有 app 退到后台才显示悬浮窗
                        if (!isAppInForeground && android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                            FloatingService.onStopClick = { engine?.stop() }
                            startService(android.content.Intent(this@MainActivity, FloatingService::class.java))
                        }
                    } else {
                        btnSend.setImageResource(R.drawable.ic_send)
                        btnSend.setBackgroundResource(R.drawable.bg_send_button)
                        btnSend.contentDescription = "发送"
                        stopService(android.content.Intent(this@MainActivity, FloatingService::class.java))
                    }
                }
            }
        })

        // 空状态欢迎语由 layout 里的 emptyState 显示，不再自动 add welcome 消息

        // 恢复历史对话到界面
        loadProjectHistory()

        // 标题栏显示当前项目名
        supportActionBar?.subtitle = engine?.currentProjectName

        Thread {
            RootShell.hasRoot()
        }.start()
    }

    private fun loadProjectHistory() {
        val emptyState = findViewById<android.view.View>(R.id.emptyState)
        engine?.let { eng ->
            adapter.clear()
            val history = eng.snapshotForUi()
            if (history.isNotEmpty()) {
                emptyState.visibility = android.view.View.GONE
                adapter.add(ChatMessage(ChatMessage.Type.SYSTEM, "📜 已恢复 ${history.size} 条历史消息"))
                history.forEach { m ->
                    val type = when (m.role) {
                        "user" -> ChatMessage.Type.USER
                        "assistant" -> ChatMessage.Type.ASSISTANT
                        "tool" -> ChatMessage.Type.TOOL_RESULT
                        else -> ChatMessage.Type.SYSTEM
                    }
                    adapter.add(ChatMessage(type, m.text))
                }
            } else {
                emptyState.visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty() && pendingImagePath == null) return
        input.setText("")
        val img = pendingImagePath
        pendingImagePath = null
        engine?.run(text.ifBlank { "请看这张图片" }, img)
        if (isAtBottom) recycler.post { recycler.smoothScrollToPosition(adapter.itemCount - 1) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val ext = contentResolver.getType(uri)?.substringAfter("/") ?: "jpg"
                val tmp = java.io.File(cacheDir, "upload_${System.currentTimeMillis()}.$ext")
                tmp.outputStream().use { out -> inputStream?.copyTo(out) }
                pendingImagePath = tmp.absolutePath
                Toast.makeText(this, "已选图片，输入描述后发送", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "选图失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() { super.onResume(); isAppInForeground = true; if (!isRunning) { try { stopService(android.content.Intent(this, FloatingService::class.java)) } catch (_: Exception) {} } }

    override fun onStop() { super.onStop(); isAppInForeground = false; if (isRunning && android.provider.Settings.canDrawOverlays(this)) { FloatingService.onStopClick = { engine?.stop() }; startService(android.content.Intent(this, FloatingService::class.java)) } }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.action_settings)
        menu.add(0, 2, 0, R.string.action_logs)
        menu.add(0, 3, 0, R.string.action_export)
        menu.add(0, 4, 0, R.string.action_stop)
        menu.add(0, 5, 0, "新建项目")
        menu.add(0, 6, 0, "切换项目")
        menu.add(0, 7, 0, "清除记忆")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
            2 -> { startActivity(Intent(this, LogViewerActivity::class.java)); true }
            3 -> {
                val r = LogManager.exportToDownload()
                adapter.add(ChatMessage(ChatMessage.Type.SYSTEM, r))
                Toast.makeText(this, r, Toast.LENGTH_LONG).show()
                true
            }
            4 -> { engine?.stop(); true }
            5 -> { showNewProjectDialog(); true }
            6 -> { showProjectListDialog(); true }
            7 -> { engine?.clearHistory(); adapter.clear(); adapter.add(ChatMessage(ChatMessage.Type.SYSTEM, "已清除当前项目记忆")); Toast.makeText(this, "已清除记忆", Toast.LENGTH_SHORT).show(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showNewProjectDialog() {
        val et = android.widget.EditText(this).apply { hint = "项目名称" }
        android.app.AlertDialog.Builder(this)
            .setTitle("新建项目")
            .setView(et)
            .setPositiveButton("创建") { _, _ ->
                val name = et.text.toString().trim().ifBlank { "新项目" }
                engine?.let { eng ->
                    val p = eng.createProject(name)
                    if (eng.switchProject(p.id, p.name)) {
                        updateSubtitle()
                        loadProjectHistory()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showProjectListDialog() {
        val projects = engine?.listProjects().orEmpty()
        if (projects.isEmpty()) {
            Toast.makeText(this, "没有项目", Toast.LENGTH_SHORT).show()
            return
        }
        val names = projects.map { "${it.name}  (${it.id.takeLast(6)})" }.toTypedArray()
        val currentId = engine?.currentProjectId ?: ""
        val checked = projects.indexOfFirst { it.id == currentId }
        android.app.AlertDialog.Builder(this)
            .setTitle("切换项目（长按删除）")
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val p = projects[which]
                dialog.dismiss()
                if (p.id == currentId) return@setSingleChoiceItems
                engine?.let { eng ->
                    if (eng.switchProject(p.id, p.name)) {
                        updateSubtitle()
                        loadProjectHistory()
                        Toast.makeText(this, "已切换到：${p.name}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "任务正在运行，无法切换", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton("删除当前项目") { _, _ ->
                engine?.let { eng ->
                    if (eng.running) {
                        Toast.makeText(this, "任务正在运行，无法删除", Toast.LENGTH_SHORT).show()
                        return@setNeutralButton
                    }
                    val id = eng.currentProjectId
                    eng.deleteProject(id)
                    val rest = eng.listProjects()
                    if (rest.isEmpty()) {
                        val p = eng.createProject("默认项目")
                        eng.switchProject(p.id, p.name)
                    } else {
                        eng.switchProject(rest[0].id, rest[0].name)
                    }
                    updateSubtitle()
                    loadProjectHistory()
                }
            }
            .show()
    }
}
