package ai.antigem.ui.drawer

import ai.antigem.domain.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryDrawerTest {
    @Test
    fun duplicateConversationIdsAppearOnceInLazyLists() {
        val first = Conversation(id = "chat-1", title = "First")
        val repeated = first.copy(title = "Stale copy")
        val second = Conversation(id = "chat-2", title = "Second")

        assertEquals(listOf(first, second), uniqueConversations(listOf(first, repeated, second)))
    }
}
