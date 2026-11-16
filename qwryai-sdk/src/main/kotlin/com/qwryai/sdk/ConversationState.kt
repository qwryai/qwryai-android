package com.qwryai.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * One conversation, ready to show: its messages, with the agent's answer growing as it streams.
 * Collect the flows from your UI, for example with `collectAsState()` in Compose.
 *
 * @param conversationId A conversation to continue. Leave null to start a new one on the first [send].
 */
class ConversationState(private val client: QwryClient, conversationId: String? = null) {
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    private val _isSending = MutableStateFlow(false)
    private val _error = MutableStateFlow<QwryException?>(null)
    private val _hasEarlier = MutableStateFlow(false)
    private var cursor: String? = null

    val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    /** True from [send] or [retry] until the answer is complete. */
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()
    /** Why the last send, retry or history load failed. Cleared by the next one. */
    val error: StateFlow<QwryException?> = _error.asStateFlow()
    /** True while older messages remain to load with [loadEarlier]. */
    val hasEarlier: StateFlow<Boolean> = _hasEarlier.asStateFlow()

    var conversationId: String? = conversationId
        private set

    suspend fun send(text: String) {
        _messages.update { it + Message("local-${UUID.randomUUID()}", Message.Role.USER, listOf(MessagePart.Text(text)), System.currentTimeMillis() / 1000) }
        val conversationId = conversationId
        answer { onDelta ->
            // A new conversation must not continue whichever one the client used last.
            if (conversationId == null) client.newConversation()
            client.send(text, conversationId) { onTextDelta = onDelta }
        }
    }

    /** Asks for a new answer in place of [messageId]. That answer and everything after it are replaced. */
    suspend fun retry(messageId: String) {
        val conversationId = conversationId ?: return
        val index = _messages.value.indexOfFirst { it.id == messageId }
        if (index < 0) return
        _messages.update { it.take(index) }
        answer { onDelta -> client.retry(conversationId, messageId) { onTextDelta = onDelta } }
    }

    /** Loads the latest page of the conversation, replacing what is shown. */
    suspend fun load() {
        val conversationId = conversationId ?: return
        page(conversationId, null) { _messages.value = it }
    }

    /** Loads the page before the oldest message shown. */
    suspend fun loadEarlier() {
        val conversationId = conversationId ?: return
        val cursor = cursor?.takeIf { _hasEarlier.value } ?: return
        page(conversationId, cursor) { earlier -> _messages.update { earlier + it } }
    }

    private suspend fun page(conversationId: String, cursor: String?, apply: (List<Message>) -> Unit) {
        _error.value = null
        try {
            val page = client.listMessages(conversationId, cursor)
            apply(page.items)
            this.cursor = page.cursor
            _hasEarlier.value = page.hasMore
        } catch (error: QwryException) { _error.value = error }
    }

    private suspend fun answer(request: suspend (onDelta: (String) -> Unit) -> ChatResponse) {
        _isSending.value = true
        _error.value = null
        val draftId = "pending-${UUID.randomUUID()}"
        _messages.update { it + Message(draftId, Message.Role.ASSISTANT, listOf(MessagePart.Text("")), System.currentTimeMillis() / 1000) }
        try {
            val response = request { delta ->
                _messages.update { list -> list.map { if (it.id == draftId) it.copy(parts = listOf(MessagePart.Text(it.text + delta))) else it } }
            }
            _messages.update { list -> list.map { if (it.id == draftId) response.message else it } }
            conversationId = response.conversationId
        } catch (error: QwryException) {
            _messages.update { list -> list.filterNot { it.id == draftId } }
            _error.value = error
        } catch (error: CancellationException) {
            _messages.update { list -> list.filterNot { it.id == draftId } }
            throw error
        } finally { _isSending.value = false }
    }
}

/** The user's conversations, ready to show. */
class ConversationListState(private val client: QwryClient) {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val _hasMore = MutableStateFlow(false)
    private val _error = MutableStateFlow<QwryException?>(null)
    private var cursor: String? = null

    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()
    val error: StateFlow<QwryException?> = _error.asStateFlow()

    /** Loads the most recently active conversations, replacing what is shown. */
    suspend fun load() = page(null) { _conversations.value = it }

    suspend fun loadMore() {
        val cursor = cursor?.takeIf { _hasMore.value } ?: return
        page(cursor) { more -> _conversations.update { it + more } }
    }

    private suspend fun page(cursor: String?, apply: (List<Conversation>) -> Unit) {
        _error.value = null
        try {
            val page = client.listConversations(cursor)
            apply(page.items)
            this.cursor = page.cursor
            _hasMore.value = page.hasMore
        } catch (error: QwryException) { _error.value = error }
    }
}
