package com.qwryai.sdk

import org.json.JSONArray
import org.json.JSONObject

/** One piece of a message: text the agent wrote, a tool it called, or what that tool returned. */
sealed interface MessagePart {
    data class Text(val text: String) : MessagePart

    /** A tool the agent asked for. Tools registered with [QwryClient.tool] run on the device. [input] is a JSON value. */
    data class ToolCall(val id: String, val name: String, val input: Any?) : MessagePart

    /** What a tool returned. [output] is a JSON value. */
    data class ToolResult(val id: String, val name: String, val output: Any?) : MessagePart
}

data class Message(
    val id: String,
    val role: Role,
    val parts: List<MessagePart>,
    /** Seconds since 1970. */
    val createdAt: Long? = null,
    val feedback: Feedback? = null,
    /** The agent's confidence in this answer, when it reported one. */
    val score: Double? = null,
) {
    enum class Role { USER, ASSISTANT }
    enum class Feedback { POSITIVE, NEGATIVE }

    /** The message's text parts joined together. */
    val text: String get() = parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }
}

/** The agent's answer to one `send` or `retry`. */
data class ChatResponse(
    val message: Message,
    val conversationId: String,
    /** The ID the server gave the message you sent. */
    val userMessageId: String?,
    /** The identified user, or null while anonymous. */
    val userId: String?,
    val finishReason: FinishReason,
    /** Message credits this answer used. */
    val credits: Double,
) {
    enum class FinishReason { STOP, ERROR, TOOL_CALLS }
}

data class Conversation(
    val id: String,
    val title: String?,
    /** Seconds since 1970. */
    val createdAt: Long,
    val updatedAt: Long,
    val userId: String?,
    val status: Status,
) {
    enum class Status { ONGOING, ENDED, TAKEN_OVER }
}

/** One page of a list. Pass [cursor] back to get the next page. */
data class Page<T>(val items: List<T>, val cursor: String?, val hasMore: Boolean, val total: Int)

/** Who the SDK is talking as. */
sealed interface AuthState {
    /** Conversations belong to this device. */
    data object Anonymous : AuthState

    /** Conversations belong to this user, on every device they sign in on. */
    data class Identified(val userId: String) : AuthState
}

/**
 * Every failure the SDK reports.
 *
 * [code] is stable. The server's codes include `AGENT_NOT_FOUND`, `AUTH_INVALID_JWT` and `RATE_LIMIT_TOO_MANY_REQUESTS`;
 * the SDK's own are [NETWORK_ERROR], [INVALID_RESPONSE] and [TOOL_LOOP_LIMIT_EXCEEDED]. [status] is null for the SDK's own.
 */
class QwryException(val status: Int?, val code: String, message: String, val details: Map<String, String>? = null) : Exception(message) {
    val isRateLimited get() = status == 429
    val isNotFound get() = status == 404
    val isCreditsExhausted get() = status == 402

    companion object {
        /** The request never got an answer. */
        const val NETWORK_ERROR = "NETWORK_ERROR"
        /** The server answered with something the SDK could not read. */
        const val INVALID_RESPONSE = "INVALID_RESPONSE"
        /** The agent kept calling tools past `maxToolLoopSteps`. */
        const val TOOL_LOOP_LIMIT_EXCEEDED = "TOOL_LOOP_LIMIT_EXCEEDED"
    }
}

internal fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key)
internal fun JSONObject.value(key: String): Any? = if (isNull(key)) null else opt(key)

internal fun partsOf(array: JSONArray?): List<MessagePart> = (0 until (array?.length() ?: 0)).map { index ->
    val part = array!!.getJSONObject(index)
    when (part.optString("type")) {
        "tool-call" -> MessagePart.ToolCall(part.getString("toolCallId"), part.optString("toolName"), part.value("input"))
        "tool-result" -> MessagePart.ToolResult(part.getString("toolCallId"), part.optString("toolName"), part.value("output"))
        else -> MessagePart.Text(part.optString("text"))
    }
}

internal fun messageOf(json: JSONObject) = Message(
    id = json.getString("id"),
    role = if (json.optString("role") == "user") Message.Role.USER else Message.Role.ASSISTANT,
    parts = partsOf(json.optJSONArray("parts")),
    createdAt = if (json.isNull("createdAt")) null else json.optLong("createdAt"),
    feedback = when (json.text("feedback")) { "positive" -> Message.Feedback.POSITIVE; "negative" -> Message.Feedback.NEGATIVE; else -> null },
    score = json.optJSONObject("metadata")?.takeUnless { it.isNull("score") }?.optDouble("score"),
)

internal fun conversationOf(json: JSONObject) = Conversation(
    id = json.getString("id"),
    title = json.text("title"),
    createdAt = json.optLong("createdAt"),
    updatedAt = json.optLong("updatedAt"),
    userId = json.text("userId"),
    status = when (json.optString("status")) { "ended" -> Conversation.Status.ENDED; "taken_over" -> Conversation.Status.TAKEN_OVER; else -> Conversation.Status.ONGOING },
)

internal fun <T> pageOf(json: JSONObject, item: (JSONObject) -> T): Page<T> {
    val data = json.getJSONArray("data")
    val pagination = json.getJSONObject("pagination")
    return Page((0 until data.length()).map { item(data.getJSONObject(it)) }, pagination.text("cursor"), pagination.optBoolean("hasMore"), pagination.optInt("total"))
}
