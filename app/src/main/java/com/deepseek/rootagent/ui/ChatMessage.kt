package com.deepseek.rootagent.ui

data class ChatMessage(
    val type: Type,
    var text: String,
    var expanded: Boolean = false
) {
    enum class Type { USER, ASSISTANT, TOOL_CALL, TOOL_RESULT, ERROR, SYSTEM }
}
