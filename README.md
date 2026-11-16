# QwryAI Android SDK

Put your QwryAI agent inside an Android app. The SDK has no UI of its own: it sends messages, streams answers, runs your client-side actions and keeps conversation history, and you draw the chat.

Requires Android 7.0 (API 24) or later. Kotlin coroutines are its only dependency.

## Before you start

In the QwryAI dashboard open your agent, go to **Channels → Android SDK**, and switch **Visibility** on in the Overview tab. The Setup tab shows your agent ID.

## Install

```kotlin
dependencies {
    implementation("com.qwryai:qwryai-sdk:0.1.0")
}
```

The SDK adds the `INTERNET` permission for you.

## Quick start

```kotlin
import com.qwryai.sdk.Qwry

val client = Qwry.create(context, "YOUR_AGENT_ID")

val response = client.send("Hello")
println(response.message.text)
```

Create one client and keep it, for example in your `Application`. Every call is a `suspend` function and safe to call from the main thread. The next `send` continues the same conversation; call `newConversation()` to start another.

## Streaming

`send` streams. Give it handlers to show the answer as it is written:

```kotlin
client.send("Where is my order?") {
    onTextDelta = { piece -> print(piece) }
    onToolCall = { call -> println("Calling ${call.name}") }
}
```

Handlers run on a background thread. `sendNonStreaming` waits for the whole answer instead.

## Compose

`ConversationState` holds one conversation's messages as flows and updates them as the answer streams:

```kotlin
@Composable
fun Chat(client: QwryClient) {
    val chat = remember { ConversationState(client) }
    val messages by chat.messages.collectAsState()
    val sending by chat.isSending.collectAsState()
    val scope = rememberCoroutineScope()

    LazyColumn { items(messages, key = { it.id }) { Text(it.text) } }
    Button(enabled = !sending, onClick = { scope.launch { chat.send("Hello") } }) { Text("Send") }
}
```

`ConversationListState` does the same for the list of conversations. `chat.error` holds the last failure.

## Client-side actions

When an action in your agent is set to run on the client, register a tool with the same name. The SDK runs it when the agent calls it, sends the result back, and carries on with the answer:

```kotlin
client.tool("get_cart") { input ->
    JSONObject().put("items", JSONArray(cart.items.map { it.name }))
}
```

Switch the action on for the Android SDK in the channel's **Actions** tab. A tool result may be up to 20 KB. If your tool throws, the agent is told the error.

## Identify your users

Until you identify a user, conversations belong to the device. To carry them across devices, sign a JWT on your server with the agent's identity verification secret (HS256, with a `user_id` or `sub` claim) and pass it in:

```kotlin
client.identify(tokenFromYourServer)
// later
client.logout()
```

Conversations the device started before signing in move to the user. The token is kept in the app's private storage, so the user stays signed in between launches. `client.authState` is a flow of who the SDK is talking as.

## History

```kotlin
val conversations = client.listConversations()
val page = client.listMessages(conversations.items[0].id)
if (page.hasMore) client.listMessages(id, cursor = page.cursor)
```

## Retry an answer

```kotlin
client.retry(conversationId, assistantMessageId)
```

## Errors

Everything throws `QwryException`. `code` is stable and `status` is the HTTP status, or null for the SDK's own codes:

| Code | Meaning |
| --- | --- |
| `AGENT_NOT_FOUND` | Wrong agent ID, or the Android SDK channel is switched off. |
| `AUTH_INVALID_JWT` | The identity token is invalid or expired. |
| `AUTH_OWNERSHIP_MISMATCH` | The conversation belongs to another device or user. |
| `RATE_LIMIT_TOO_MANY_REQUESTS` | Too many requests; try again later. |
| `CHAT_CREDITS_EXHAUSTED` | The agent's message limit is reached. |
| `NETWORK_ERROR` | The request never got an answer. |
| `INVALID_RESPONSE` | The server answered with something the SDK could not read. |
| `TOOL_LOOP_LIMIT_EXCEEDED` | The agent kept calling tools past `maxToolLoopSteps` (10 by default). |

`error.isRateLimited`, `isNotFound` and `isCreditsExhausted` cover the usual checks.

## License

MIT
