package com.situ.aichat.data.remote.llm

/** Mirrors iOS `LLMError` with localized (zh) user-facing messages. */
sealed class LlmError(message: String) : Exception(message) {
    data object InvalidUrl : LlmError("API URL 无效，请检查配置。") {
        private fun readResolve(): Any = InvalidUrl
    }

    data object InvalidResponse : LlmError("服务器返回了无效响应。") {
        private fun readResolve(): Any = InvalidResponse
    }

    data class Http(val statusCode: Int, val bodySummary: String?) :
        LlmError(buildMessage(statusCode, bodySummary))

    data object DecodingError : LlmError("解析响应失败，模型名可能不正确。") {
        private fun readResolve(): Any = DecodingError
    }

    data object Timeout : LlmError("请求超时，请检查网络连接。") {
        private fun readResolve(): Any = Timeout
    }

    data class Stream(val detail: String) : LlmError("流式错误：$detail")

    companion object {
        private fun buildMessage(code: Int, body: String?): String {
            val base = when (code) {
                401 -> "鉴权失败 (401)，请检查 API Key 是否正确。"
                403 -> "访问被拒 (403)，API Key 无权限。"
                404 -> "端点不存在 (404)，请检查 Base URL 和模型名。"
                429 -> "请求过于频繁 (429)，请稍后再试。"
                in 500..599 -> "服务器错误 ($code)，服务暂时不可用。"
                else -> "HTTP 错误：$code"
            }
            return if (!body.isNullOrEmpty()) "$base - $body" else base
        }
    }
}
