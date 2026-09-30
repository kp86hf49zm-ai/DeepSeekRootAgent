package com.deepseek.rootagent

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    // 预设服务商：名称 -> (baseUrl, 模型列表)
    private val providers = linkedMapOf(
        "DeepSeek" to ("https://api.deepseek.com" to listOf("deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash")),
        "OpenAI" to ("https://api.openai.com/v1" to listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo")),
        "通义千问" to ("https://dashscope.aliyuncs.com/compatible-mode/v1" to listOf("qwen-plus", "qwen-turbo", "qwen-max")),
        "Moonshot" to ("https://api.moonshot.cn/v1" to listOf("moonshot-v1-8k", "moonshot-v1-32k")),
        "OpenRouter" to ("https://openrouter.ai/api/v1" to listOf("openai/gpt-4o", "anthropic/claude-sonnet-4", "deepseek/deepseek-chat"))
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val etKey = findViewById<EditText>(R.id.etApiKey)
        val etUrl = findViewById<EditText>(R.id.etBaseUrl)
        val spProvider = findViewById<Spinner>(R.id.spProvider)
        val spModel = findViewById<Spinner>(R.id.spModel)
        val etTimeout = findViewById<EditText>(R.id.etTimeout)
        val etMax = findViewById<EditText>(R.id.etMaxSteps)
        val cbRoot = findViewById<CheckBox>(R.id.cbRootCheck)
        val tvStatus = findViewById<TextView>(R.id.tvRootStatus)

        etKey.setText(Prefs.get(this, Prefs.KEY_API_KEY, ""))
        etUrl.setText(Prefs.get(this, Prefs.KEY_BASE_URL, "https://api.deepseek.com"))
        etTimeout.setText(Prefs.getInt(this, Prefs.KEY_TIMEOUT, 60).toString())
        etMax.setText(Prefs.getInt(this, Prefs.KEY_MAX_STEPS, 15).toString())
        cbRoot.isChecked = Prefs.getBool(this, Prefs.KEY_CHECK_ROOT, true)

        // 服务商下拉
        val providerNames = providers.keys.toList()
        val providerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providerNames)
        spProvider.adapter = providerAdapter
        val savedUrl = Prefs.get(this, Prefs.KEY_BASE_URL, "https://api.deepseek.com")
        val savedProvider = providers.entries.firstOrNull { it.value.first == savedUrl }?.key ?: "DeepSeek"
        spProvider.setSelection(providerNames.indexOf(savedProvider))

        // 模型下拉，根据选中的服务商刷新
        fun refreshModels(provider: String) {
            val models = providers[provider]?.second ?: listOf()
            val mAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
            spModel.adapter = mAdapter
            val savedModel = Prefs.get(this, Prefs.KEY_MODEL, models.firstOrNull() ?: "")
            spModel.setSelection(models.indexOf(savedModel).takeIf { it >= 0 } ?: 0)
            etUrl.setText(providers[provider]?.first ?: "")
        }
        refreshModels(savedProvider)

        spProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                refreshModels(providerNames[pos])
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.set(this, Prefs.KEY_API_KEY, etKey.text.toString().trim())
            Prefs.set(this, Prefs.KEY_BASE_URL, etUrl.text.toString().trim())
            val selectedProvider = providerNames[spProvider.selectedItemPosition]
            val models = providers[selectedProvider]?.second ?: listOf()
            Prefs.set(this, Prefs.KEY_MODEL, models[spModel.selectedItemPosition])
            Prefs.setInt(this, Prefs.KEY_TIMEOUT, etTimeout.text.toString().toIntOrNull() ?: 60)
            Prefs.setInt(this, Prefs.KEY_MAX_STEPS, etMax.text.toString().toIntOrNull() ?: 15)
            Prefs.setBool(this, Prefs.KEY_CHECK_ROOT, cbRoot.isChecked)
            finish()
        }

        Thread {
            val ok = RootShell.hasRoot()
            runOnUiThread { tvStatus.text = "Root 状态：" + if (ok) "✅ 已授权" else "❌ 未授权" }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}