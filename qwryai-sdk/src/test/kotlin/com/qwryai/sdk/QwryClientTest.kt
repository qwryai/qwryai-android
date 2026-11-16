package com.qwryai.sdk

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Answers every request from a queue and records what was sent. */
private class FakeServer(vararg answers: Pair<Int, String>) : Http {
    private val answers = ArrayDeque(answers.toList())
    val sent = mutableListOf<HttpRequest>()
    override fun execute(request: HttpRequest): HttpResponse {
        sent.add(request)
        val (status, body) = answers.removeFirst()
        return HttpResponse(status, body.byteInputStream())
    }
    fun body(index: Int) = JSONObject(sent[index].body!!)
}

private class MemoryStore : Store {
    override val deviceId = "device-1"
    override var token: String? = null
    override var userId: String? = null
}

private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
private const val FINISH = """{"type":"finish","finishReason":"stop","messageMetadata":{"messageId":"m1","conversationId":"c1","userMessageId":"u1","userId":null,"usage":{"credits":1}}}"""
private val TOOL_FINISH = FINISH.replace("\"finishReason\":\"stop\"", "\"finishReason\":\"tool-calls\"")
private const val EMPTY_PAGE = """{"data":[],"pagination":{"cursor":null,"hasMore":false,"total":0}}"""

class QwryClientTest {
    private val store = MemoryStore()
    private fun client(server: Http, maxSteps: Int = 10) = QwryClient("agent", "https://example.test/api/sdk", store, server, maxSteps)

    @Test fun sendStreamsTheAnswerAndKeepsTheConversation() = runTest {
        val server = FakeServer(
            200 to sse("""{"type":"start","messageId":"m1"}""", """{"type":"text-start","id":"text_0"}""", """{"type":"text-delta","id":"text_0","delta":"Hel"}""", """{"type":"text-delta","id":"text_0","delta":"lo"}""", FINISH),
            200 to sse("""{"type":"text-delta","id":"text_0","delta":"Again"}""", FINISH))
        val client = client(server)
        val deltas = mutableListOf<String>()
        val response = client.send("Hi") { onTextDelta = { deltas.add(it) } }
        assertEquals(listOf("Hel", "lo"), deltas)
        assertEquals("Hello", response.message.text)
        assertEquals("m1", response.message.id)
        assertEquals("c1", response.conversationId)
        assertNull(response.userId)
        assertEquals(1.0, response.credits, 0.0)
        val first = server.sent[0]
        assertEquals("POST", first.method)
        assertEquals("https://example.test/api/sdk/agents/agent/chat", first.url)
        assertEquals("QwryAI-Android-SDK/${Qwry.VERSION}", first.headers["User-Agent"])
        assertEquals("device-1", first.headers["X-Device-Id"])
        assertNull(first.headers["X-User-Token"])
        assertEquals("Hi", server.body(0).getString("message"))
        assertTrue(server.body(0).getBoolean("stream"))
        assertFalse(server.body(0).has("conversationId"))
        client.send("More")
        assertEquals("c1", server.body(1).getString("conversationId"))
        client.newConversation()
        assertNull(client.currentConversationId)
    }

    @Test fun runsRegisteredToolsAndSendsTheirResults() = runTest {
        val server = FakeServer(
            200 to sse("""{"type":"tool-input-available","toolCallId":"t1","toolName":"get_cart","input":{"limit":2}}""", TOOL_FINISH),
            200 to """{"data":{"success":true}}""",
            200 to sse("""{"type":"text-delta","id":"text_0","delta":"Two items"}""", FINISH))
        val client = client(server)
        client.tool("get_cart") { input -> JSONObject().put("items", input.getInt("limit")) }
        val response = client.send("What is in my cart?")
        assertEquals("Two items", response.message.text)
        assertEquals("https://example.test/api/sdk/agents/agent/conversations/c1/tool-result", server.sent[1].url)
        assertEquals("t1", server.body(1).getString("toolCallId"))
        assertEquals(2, server.body(1).getJSONObject("output").getInt("items"))
        assertEquals("c1", server.body(2).getString("conversationId"))
        assertFalse(server.body(2).has("message"))
    }

    @Test fun aToolThatIsMissingOrThrowsReportsAnError() = runTest {
        val round = sse("""{"type":"tool-input-available","toolCallId":"t1","toolName":"broken","input":{}}""", TOOL_FINISH)
        val server = FakeServer(200 to round, 200 to "{}", 200 to sse(FINISH), 200 to round, 200 to "{}", 200 to sse(FINISH))
        val client = client(server)
        client.send("Go")
        assertEquals("No tool named broken is registered in the app", server.body(1).getJSONObject("output").getString("error"))
        client.tool("broken") { error("Cart is offline") }
        client.send("Go")
        assertEquals("Cart is offline", server.body(4).getJSONObject("output").getString("error"))
    }

    @Test fun stopsWhenTheAgentKeepsCallingTools() = runTest {
        val round = sse("""{"type":"tool-input-available","toolCallId":"t1","toolName":"loop","input":{}}""", TOOL_FINISH)
        val client = client(FakeServer(200 to round, 200 to "{}", 200 to round, 200 to "{}", 200 to round), maxSteps = 2)
        client.tool("loop") { null }
        try { client.send("Go"); fail("expected the loop limit") } catch (error: QwryException) { assertEquals(QwryException.TOOL_LOOP_LIMIT_EXCEEDED, error.code) }
    }

    @Test fun serverToolsAreNotRunOnTheDevice() = runTest {
        val server = FakeServer(200 to sse("""{"type":"tool-input-available","toolCallId":"t1","toolName":"stock","input":{"id":42}}""",
            """{"type":"tool-output-available","toolCallId":"t1","output":{"stock":53}}""", """{"type":"text-delta","id":"text_0","delta":"In stock"}""", FINISH))
        val parts = client(server).send("Stock?").message.parts
        assertEquals(3, parts.size)
        assertEquals("stock", (parts[0] as MessagePart.ToolCall).name)
        assertEquals(53, ((parts[1] as MessagePart.ToolResult).output as JSONObject).getInt("stock"))
        assertEquals("stock", (parts[1] as MessagePart.ToolResult).name)
        assertEquals(MessagePart.Text("In stock"), parts[2])
        assertEquals(1, server.sent.size)
    }

    @Test fun nonStreamingAnswer() = runTest {
        val server = FakeServer(200 to """{"data":{"id":"m1","role":"assistant","parts":[{"type":"text","text":"Hello"}],"metadata":{"conversationId":"c1","userMessageId":"u1","userId":"user-1","finishReason":"stop","usage":{"credits":2}}}}""")
        val response = client(server).sendNonStreaming("Hi")
        assertEquals("Hello", response.message.text)
        assertEquals("user-1", response.userId)
        assertEquals(2.0, response.credits, 0.0)
        assertFalse(server.body(0).getBoolean("stream"))
    }

    @Test fun serverErrorsKeepTheirCode() = runTest {
        val client = client(FakeServer(
            404 to """{"error":{"code":"AGENT_NOT_FOUND","message":"Agent not found"}}""",
            400 to """{"error":{"code":"VALIDATION_INVALID_BODY","message":"Invalid request","details":{"limit":"Too big"}}}""",
            502 to "<html>"))
        try { client.send("Hi"); fail("expected an error") } catch (error: QwryException) {
            assertEquals("AGENT_NOT_FOUND", error.code); assertEquals("Agent not found", error.message); assertTrue(error.isNotFound)
        }
        try { client.listConversations(); fail("expected an error") } catch (error: QwryException) { assertEquals(mapOf("limit" to "Too big"), error.details) }
        try { client.listConversations(); fail("expected an error") } catch (error: QwryException) { assertEquals(502, error.status); assertEquals("INTERNAL_SERVER_ERROR", error.code) }
    }

    @Test fun aStreamErrorFailsTheSend() = runTest {
        val client = client(FakeServer(200 to sse("""{"type":"error","errorText":"Out of credits"}""", FINISH)))
        try { client.send("Hi"); fail("expected an error") } catch (error: QwryException) {
            assertEquals("CHAT_STREAMING_ERROR", error.code); assertEquals("Out of credits", error.message)
        }
    }

    @Test fun aLostConnectionIsANetworkError() = runTest {
        val client = client(Http { throw java.io.IOException("offline") })
        try { client.send("Hi"); fail("expected an error") } catch (error: QwryException) { assertEquals(QwryException.NETWORK_ERROR, error.code); assertNull(error.status) }
    }

    @Test fun identifyKeepsTheTokenAndLogoutDropsIt() = runTest {
        val server = FakeServer(200 to """{"data":{"userId":"user-1"}}""", 200 to EMPTY_PAGE, 200 to EMPTY_PAGE)
        val client = client(server)
        assertEquals(AuthState.Anonymous, client.authState.value)
        assertEquals("user-1", client.identify("a.b.c"))
        assertEquals("https://example.test/api/sdk/agents/agent/verify", server.sent[0].url)
        assertEquals("a.b.c", server.body(0).getString("token"))
        assertEquals("a.b.c", store.token)
        assertEquals(AuthState.Identified("user-1"), client.authState.value)
        client.listConversations()
        assertEquals("a.b.c", server.sent[1].headers["X-User-Token"])
        // The next launch is still signed in.
        assertEquals("user-1", client(server).currentUserId)
        client.logout()
        assertEquals(AuthState.Anonymous, client.authState.value)
        assertNull(store.token)
        client.listConversations()
        assertNull(server.sent[2].headers["X-User-Token"])
    }

    @Test fun historyPages() = runTest {
        val server = FakeServer(
            200 to """{"data":[{"id":"c1","title":"Hoodie","createdAt":1700000000,"updatedAt":1700000100,"status":"taken_over"}],"pagination":{"cursor":"next","hasMore":true,"total":3}}""",
            200 to """{"data":[{"id":"u1","role":"user","parts":[{"type":"text","text":"Hi"}],"createdAt":1700000000},{"id":"m1","role":"assistant","parts":[{"type":"tool-call","toolCallId":"t","toolName":"stock","input":{}},{"type":"text","text":"Hello"}],"createdAt":1700000001,"feedback":"positive","metadata":{"score":0.9}}],"pagination":{"cursor":null,"hasMore":false,"total":2}}""")
        val client = client(server)
        val conversations = client.listConversations(limit = 1)
        assertEquals("Hoodie", conversations.items[0].title)
        assertEquals(Conversation.Status.TAKEN_OVER, conversations.items[0].status)
        assertNull(conversations.items[0].userId)
        assertEquals(1700000100L, conversations.items[0].updatedAt)
        assertEquals("next", conversations.cursor); assertTrue(conversations.hasMore); assertEquals(3, conversations.total)
        assertTrue(server.sent[0].url.endsWith("/conversations?limit=1"))
        val messages = client.listMessages("c1", cursor = "a=b")
        assertTrue(server.sent[1].url.endsWith("/conversations/c1/messages?limit=20&cursor=a%3Db"))
        assertEquals(listOf(Message.Role.USER, Message.Role.ASSISTANT), messages.items.map { it.role })
        assertEquals("Hello", messages.items[1].text)
        assertEquals(Message.Feedback.POSITIVE, messages.items[1].feedback)
        assertEquals(0.9, messages.items[1].score!!, 0.0)
        assertNull(messages.items[0].score); assertNull(messages.cursor)
    }

    @Test fun conversationStateShowsTheExchange() = runTest {
        val server = FakeServer(200 to sse("""{"type":"text-delta","id":"text_0","delta":"Hello"}""", FINISH), 404 to """{"error":{"code":"AGENT_NOT_FOUND","message":"Agent not found"}}""")
        val chat = ConversationState(client(server))
        chat.send("Hi")
        assertEquals(listOf("Hi", "Hello"), chat.messages.value.map { it.text })
        assertEquals("m1", chat.messages.value.last().id)
        assertEquals("c1", chat.conversationId)
        assertFalse(chat.isSending.value)
        chat.send("Again")
        assertEquals(listOf("Hi", "Hello", "Again"), chat.messages.value.map { it.text })
        assertEquals("c1", server.body(1).getString("conversationId"))
        assertTrue(chat.error.value!!.isNotFound)
    }
}
