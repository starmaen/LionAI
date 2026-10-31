package com.lion.ai

data class ChatTurn(val role: String, val content: String)

class LlmException(val status: Int, message: String) : Exception(message)
