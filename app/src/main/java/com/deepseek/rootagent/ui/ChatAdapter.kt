package com.deepseek.rootagent.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.deepseek.rootagent.R

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {
    private val items = mutableListOf<ChatMessage>()

    fun add(msg: ChatMessage) { items.add(msg); notifyItemInserted(items.size - 1) }

    fun appendTool(block: String) {
        if (items.isNotEmpty() && items.last().type == ChatMessage.Type.TOOL_RESULT && !items.last().expanded) {
            val last = items.last()
            last.text = last.text + "\n" + block
            notifyItemChanged(items.size - 1)
        } else {
            add(ChatMessage(ChatMessage.Type.TOOL_RESULT, block))
        }
    }

    fun clear() { items.clear(); notifyDataSetChanged() }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val row: LinearLayout = v.findViewById(R.id.rowRoot)
        val role: TextView = v.findViewById(R.id.tvRole)
        val content: TextView = v.findViewById(R.id.tvContent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_text, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()

        val isUser = m.type == ChatMessage.Type.USER

        holder.content.text = m.text

        if (m.type == ChatMessage.Type.TOOL_RESULT) {
            holder.content.setSingleLine(!m.expanded)
            holder.content.maxLines = if (m.expanded) Int.MAX_VALUE else 1
            holder.content.ellipsize = TextUtils.TruncateAt.END
            holder.content.setOnClickListener { m.expanded = !m.expanded; notifyItemChanged(position) }
            holder.content.isClickable = true
        } else {
            holder.content.setSingleLine(false)
            holder.content.maxLines = Int.MAX_VALUE
            holder.content.ellipsize = null
            holder.content.setOnClickListener(null)
            holder.content.isClickable = false
        }

        val contentLp = holder.content.layoutParams as LinearLayout.LayoutParams
        contentLp.gravity = if (isUser) Gravity.END else Gravity.START
        holder.content.layoutParams = contentLp
        holder.role.gravity = if (isUser) Gravity.END else Gravity.START

        when (m.type) {
            ChatMessage.Type.USER -> {
                holder.content.setTextColor(Color.parseColor("#1A1A1A"))
                val d = GradientDrawable(); d.setColor(Color.parseColor("#F1F3F5")); d.cornerRadius = dp(16f).toFloat()
                holder.content.background = d
                holder.content.setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                holder.content.typeface = android.graphics.Typeface.DEFAULT
                holder.content.textSize = 15f
            }
            ChatMessage.Type.ASSISTANT -> {
                holder.content.setTextColor(Color.parseColor("#1A1A1A"))
                holder.content.background = null
                holder.content.setPadding(0, dp(6f), 0, dp(6f))
                holder.content.typeface = android.graphics.Typeface.DEFAULT
                holder.content.textSize = 15f
                holder.content.setLineSpacing(0f, 1.4f)
            }
            ChatMessage.Type.TOOL_CALL -> {
                holder.content.setTextColor(Color.parseColor("#666666"))
                val d = GradientDrawable(); d.setColor(Color.parseColor("#F7F8FA")); d.cornerRadius = dp(8f).toFloat()
                holder.content.background = d
                holder.content.setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                holder.content.typeface = android.graphics.Typeface.MONOSPACE
                holder.content.textSize = 12f
            }
            ChatMessage.Type.TOOL_RESULT -> {
                holder.content.setTextColor(Color.parseColor("#888888"))
                val d = GradientDrawable(); d.setColor(Color.parseColor("#F7F8FA")); d.cornerRadius = dp(8f).toFloat()
                holder.content.background = d
                holder.content.setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                holder.content.typeface = android.graphics.Typeface.MONOSPACE
                holder.content.textSize = 12f
            }
            ChatMessage.Type.ERROR -> {
                holder.content.setTextColor(Color.parseColor("#C62828"))
                val d = GradientDrawable(); d.setColor(Color.parseColor("#FFEBEE")); d.cornerRadius = dp(8f).toFloat()
                holder.content.background = d
                holder.content.setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                holder.content.typeface = android.graphics.Typeface.MONOSPACE
                holder.content.textSize = 12f
            }
            ChatMessage.Type.SYSTEM -> {
                holder.content.setTextColor(Color.parseColor("#AAAAAA"))
                holder.content.background = null
                holder.content.setPadding(0, dp(4f), 0, dp(4f))
                holder.content.typeface = android.graphics.Typeface.DEFAULT
                holder.content.textSize = 12f
            }
        }

        holder.role.text = ""
    }
}