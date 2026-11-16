package com.qwryai.sdk

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Creates the client. */
object Qwry {
    const val VERSION = "0.1.0"
    const val DEFAULT_BASE_URL = "https://www.qwryai.com/api/sdk"

    /**
     * Create one client for your agent and keep it, for example in your `Application`.
     *
     * @param agentId The agent's ID, from its Android SDK page in the dashboard.
     * @param baseUrl The SDK API to call. Change it only for a self-hosted or test server.
     * @param maxToolLoopSteps How many rounds of tool calls one `send` may make before it gives up.
     */
    @JvmStatic
    @JvmOverloads
    fun create(context: Context, agentId: String, baseUrl: String = DEFAULT_BASE_URL, maxToolLoopSteps: Int = 10): QwryClient =
        QwryClient(agentId, baseUrl, PreferencesStore(context), UrlConnectionHttp(), maxToolLoopSteps)
}

/** What to hear about while an answer is being written. The callbacks run on a background thread. */
class SendHandlers {
    /** A piece of the agent's text, as it arrives. */
    var onTextDelta: ((String) -> Unit)? = null
    /** The agent called a tool: one you registered, or one the server runs. */
    var onToolCall: ((MessagePart.ToolCall) -> Unit)? = null
    /** A tool finished. */
    var onToolResult: ((MessagePart.ToolResult) -> Unit)? = null
}

/**
 * Talks to one QwryAI agent.
 *
 * ```kotlin
 * val client = Qwry.create(context, "YOUR_AGENT_ID")
 * val response = client.send("Hello")
 * println(response.message.text)
 * ```
 */
class QwryClient internal constructor(
    val agentId: String,
    baseUrl: String,
    private val store: Store,
    private val http: Http,
    private val maxToolLoopSteps: Int,
) {
    private val root = "${baseUrl.trimEnd('/')}/agents/$agentId"
    private val tools = ConcurrentHashMap<String, suspend (JSONObject) -> Any?>()
    private val auth = MutableStateFlow(store.userId?.takeIf { store.token != null }?.let { AuthState.Identified(it) } ?: AuthState.Anonymous)

    /** Identifies this install. Anonymous conversations belong to it. */
    val deviceId: String get() = store.deviceId

    /** The conversation `send` continues. Null until the first answer, and after [newConversation]. */
    @Volatile
    var currentConversationId: String? = null
        private set

    val authState: StateFlow<AuthState> = auth.asStateFlow()
    val currentUserId: String? get() = (auth.value as? AuthState.Identified)?.userId

    // Chat

    /**
     * Sends a message and returns the agent's answer once it is complete, calling [handlers] as it streams.
     * Continues [conversationId], or the current conversation, or starts a new one.
     */
    suspend fun send(text: String, conversationId: String? = null, handlers: SendHandlers.() -> Unit = {}): ChatResponse =
        converse("chat", JSONObject().put("message", text), conversationId ?: currentConversationId, true, SendHandlers().apply(handlers))

    /** Like [send], but the answer arrives in one response instead of a stream. */
    suspend fun sendNonStreaming(text: String, conversationId: String? = null): ChatResponse =
        converse("chat", JSONObject().put("message", text), conversationId ?: currentConversationId, false, SendHandlers())

    /** Asks the agent to answer again in place of [messageId]. That answer and everything after it are replaced. */
    suspend fun retry(conversationId: String, messageId: String, handlers: SendHandlers.() -> Unit = {}): ChatResponse =
        converse("conversations/$conversationId/retry", JSONObject().put("messageId", messageId), null, true, SendHandlers().apply(handlers))

    /** Forgets the current conversation. The next `send` starts a new one. */
    fun newConversation() { currentConversationId = null }

    // Tools

    /**
     * Runs [handler] on this device when the agent calls the client-side action named [name].
     * What it returns (a JSON value: `JSONObject`, `JSONArray`, string, number, boolean or null) goes back to the agent;
     * if it throws, the agent is told the error.
     */
    fun tool(name: String, handler: suspend (input: JSONObject) -> Any?) { tools[name] = handler }
    fun removeTool(name: String) { tools.remove(name) }

    // History

    /** This user's conversations (this device's while anonymous), most recently active first. */
    suspend fun listConversations(cursor: String? = null, limit: Int = 20): Page<Conversation> =
        pageOf(json(call("GET", "conversations" + query(cursor, limit))), ::conversationOf)

    /** A page of a conversation, oldest message first. [cursor] reaches further back. */
    suspend fun listMessages(conversationId: String, cursor: String? = null, limit: Int = 20): Page<Message> =
        pageOf(json(call("GET", "conversations/$conversationId/messages" + query(cursor, limit))), ::messageOf)

    // Identity

    /**
     * Signs the user in with a JWT your server made with the agent's identity verification secret.
     * Conversations this device started anonymously move to the user. Returns the user's ID.
     */
    suspend fun identify(token: String): String {
        val userId = read { json(call("POST", "verify", JSONObject().put("token", token))).getJSONObject("data").getString("userId") }
        store.token = token
        store.userId = userId
        auth.value = AuthState.Identified(userId)
        currentConversationId = null
        return userId
    }

    /** Signs the user out. The SDK goes back to this device's own conversations. */
    fun logout() {
        store.token = null
        store.userId = null
        auth.value = AuthState.Anonymous
        currentConversationId = null
    }

    // Requests

    private fun query(cursor: String?, limit: Int) = "?limit=$limit" + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")

    private suspend fun call(method: String, path: String, body: JSONObject? = null): HttpResponse = withContext(Dispatchers.IO) {
        val headers = buildMap {
            put("User-Agent", "QwryAI-Android-SDK/${Qwry.VERSION}")
            put("X-Device-Id", store.deviceId)
            store.token?.let { put("X-User-Token", it) }
            if (body != null) put("Content-Type", "application/json")
        }
        try { http.execute(HttpRequest(method, "$root/$path", headers, body?.toString())) } catch (error: IOException) { throw network(error) }
    }

    private fun network(error: IOException) = QwryException(null, QwryException.NETWORK_ERROR, error.message ?: "The request failed")

    private inline fun <T> read(block: () -> T): T =
        try { block() } catch (error: JSONException) { throw QwryException(null, QwryException.INVALID_RESPONSE, "The server sent a response the SDK could not read.") }

    /** The body of a successful response; a refusal becomes a [QwryException] with the server's code. */
    private suspend fun json(response: HttpResponse): JSONObject = withContext(Dispatchers.IO) {
        val text = try { response.body.bufferedReader().use { it.readText() } } catch (error: IOException) { throw network(error) }
        if (response.status in 200..299) return@withContext read { JSONObject(text) }
        throw refusal(response.status, text)
    }

    private fun refusal(status: Int, text: String): QwryException {
        val error = try { JSONObject(text).optJSONObject("error") } catch (_: JSONException) { null }
            ?: return QwryException(status, "INTERNAL_SERVER_ERROR", "Something went wrong, please try again")
        val details = error.optJSONObject("details")?.let { fields -> fields.keys().asSequence().associateWith { fields.optString(it) } }
        return QwryException(status, error.optString("code"), error.optString("message"), details)
    }

    // Turns

    private class Turn(val response: ChatResponse, val pending: List<MessagePart.ToolCall>)

    /** One request and its answer, then the tool rounds the agent asks for. */
    private suspend fun converse(path: String, body: JSONObject, conversationId: String?, stream: Boolean, handlers: SendHandlers): ChatResponse {
        conversationId?.let { body.put("conversationId", it) }
        var turn = perform(path, body, stream, handlers)
        var steps = 0
        while (turn.response.finishReason == ChatResponse.FinishReason.TOOL_CALLS && turn.pending.isNotEmpty()) {
            if (++steps > maxToolLoopSteps) throw QwryException(null, QwryException.TOOL_LOOP_LIMIT_EXCEEDED, "The agent called too many tools in a row.")
            val conversation = turn.response.conversationId
            for (call in turn.pending) {
                val output = run(call)
                handlers.onToolResult?.invoke(MessagePart.ToolResult(call.id, call.name, output))
                submit(output, call, conversation)
            }
            // No message: the agent carries on from the tool results.
            turn = perform("chat", JSONObject().put("conversationId", conversation), stream, handlers)
        }
        return turn.response
    }

    private suspend fun run(call: MessagePart.ToolCall): Any? {
        val handler = tools[call.name] ?: return JSONObject().put("error", "No tool named ${call.name} is registered in the app")
        return try { handler(call.input as? JSONObject ?: JSONObject()) } catch (error: CancellationException) { throw error } catch (error: Exception) {
            JSONObject().put("error", error.message ?: error.toString())
        }
    }

    /** A lost tool result would leave the conversation waiting, so a failed submit is tried again. */
    private suspend fun submit(output: Any?, call: MessagePart.ToolCall, conversationId: String) {
        val body = JSONObject().put("toolCallId", call.id).put("output", output ?: JSONObject.NULL)
        var wait = 300L
        for (attempt in 0..3) {
            try { json(call("POST", "conversations/$conversationId/tool-result", body)); return } catch (error: QwryException) {
                if (error.code != QwryException.NETWORK_ERROR || attempt == 3) throw error
                delay(wait); wait *= 2
            }
        }
    }

    private suspend fun perform(path: String, body: JSONObject, stream: Boolean, handlers: SendHandlers): Turn {
        val response = call("POST", path, JSONObject(body.toString()).put("stream", stream))
        val turn = if (stream) streamed(response, handlers) else whole(json(response), handlers)
        currentConversationId = turn.response.conversationId
        return turn
    }

    private fun whole(json: JSONObject, handlers: SendHandlers): Turn = read {
        val data = json.getJSONObject("data")
        val metadata = data.getJSONObject("metadata")
        val answer = Answer(handlers)
        for (part in partsOf(data.optJSONArray("parts"))) when (part) {
            is MessagePart.Text -> answer.text(part.text, startsPart = true)
            is MessagePart.ToolCall -> answer.call(part)
            is MessagePart.ToolResult -> answer.result(part.id, part.output)
        }
        answer.turn(data.getString("id"), metadata.getString("conversationId"), metadata.text("userMessageId"), metadata.text("userId"),
            metadata.text("finishReason"), metadata.optJSONObject("usage")?.optDouble("credits", 0.0) ?: 0.0)
    }

    private suspend fun streamed(response: HttpResponse, handlers: SendHandlers): Turn = withContext(Dispatchers.IO) {
        if (response.status !in 200..299) json(response)
        val answer = Answer(handlers)
        var messageId: String? = null
        var finish: JSONObject? = null
        var failure: String? = null
        try {
            response.body.bufferedReader().useLines { lines ->
                for (line in lines) {
                    coroutineContext.ensureActive()
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    val event = try { JSONObject(payload) } catch (_: JSONException) { continue }
                    when (event.optString("type")) {
                        "start" -> messageId = event.text("messageId")
                        "text-start" -> answer.text("", startsPart = true)
                        "text-delta" -> answer.text(event.optString("delta"), startsPart = false)
                        "tool-input-available" -> event.text("toolCallId")?.let { answer.call(MessagePart.ToolCall(it, event.optString("toolName"), event.value("input"))) }
                        "tool-output-available" -> event.text("toolCallId")?.let { answer.result(it, event.value("output")) }
                        "tool-output-error" -> event.text("toolCallId")?.let { answer.result(it, JSONObject().put("error", event.text("errorText") ?: "The tool failed")) }
                        "error" -> failure = event.text("errorText") ?: "Something went wrong, please try again"
                        "finish" -> finish = event
                    }
                }
            }
        } catch (error: IOException) { throw network(error) }
        failure?.let { throw QwryException(500, "CHAT_STREAMING_ERROR", it) }
        val metadata = finish?.optJSONObject("messageMetadata")
        val conversationId = metadata?.text("conversationId") ?: throw QwryException(null, QwryException.INVALID_RESPONSE, "The server sent a response the SDK could not read.")
        answer.turn(metadata.text("messageId") ?: messageId ?: UUID.randomUUID().toString(), conversationId, metadata.text("userMessageId"), metadata.text("userId"),
            finish?.text("finishReason"), metadata.optJSONObject("usage")?.optDouble("credits", 0.0) ?: 0.0)
    }

    /** Builds the answer's parts as they arrive and tells the app's handlers. */
    private class Answer(private val handlers: SendHandlers) {
        private val parts = mutableListOf<MessagePart>()
        private val calls = mutableListOf<MessagePart.ToolCall>()
        private val answered = mutableSetOf<String>()
        private var textOpen = false

        fun text(text: String, startsPart: Boolean) {
            if (startsPart || !textOpen) { parts.add(MessagePart.Text("")); textOpen = true }
            if (text.isEmpty()) return
            parts[parts.lastIndex] = MessagePart.Text((parts.last() as MessagePart.Text).text + text)
            handlers.onTextDelta?.invoke(text)
        }

        fun call(call: MessagePart.ToolCall) {
            textOpen = false
            calls.add(call); parts.add(call)
            handlers.onToolCall?.invoke(call)
        }

        fun result(id: String, output: Any?) {
            textOpen = false
            answered.add(id)
            val result = MessagePart.ToolResult(id, calls.firstOrNull { it.id == id }?.name ?: "", output)
            parts.add(result)
            handlers.onToolResult?.invoke(result)
        }

        fun turn(messageId: String, conversationId: String, userMessageId: String?, userId: String?, finishReason: String?, credits: Double): Turn {
            val kept = parts.filterNot { it is MessagePart.Text && it.text.isEmpty() }
            val reason = when (finishReason) { "tool-calls" -> ChatResponse.FinishReason.TOOL_CALLS; "error" -> ChatResponse.FinishReason.ERROR; else -> ChatResponse.FinishReason.STOP }
            val message = Message(messageId, Message.Role.ASSISTANT, kept, System.currentTimeMillis() / 1000)
            return Turn(ChatResponse(message, conversationId, userMessageId, userId, reason, credits), calls.filterNot { it.id in answered })
        }
    }
}
