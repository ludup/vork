package sh.vork.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.security.ToolSuspensionException;
import sh.vork.database.mock.MapDatabaseRepository;
import sh.vork.storage.FileStorageService;

class ChatServiceSuspensionPersistenceTest {

    @Test
    void sendMessage_whenToolSuspended_persistsAwaitingAuthorizationSnapshot() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        AiOrchestrationService aiService = mock(AiOrchestrationService.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);

        String sessionId = "session-1";
        AiSession initial = new AiSession(sessionId, AiProvider.GEMINI.name(), 123L, List.of(), null);
        sessionRepo.save(initial);

        when(aiService.generateWithHistory(org.mockito.ArgumentMatchers.<org.springframework.ai.chat.messages.Message>anyList(),
            anyString(), any(AiProvider.class)))
                .thenThrow(new ToolSuspensionException("compileJavaType", "{\"source\":\"class Demo {}\"}"));

        // Ensure media path is not accidentally used in this scenario.
        when(aiService.generateWithHistoryAndMedia(
            org.mockito.ArgumentMatchers.<org.springframework.ai.chat.messages.Message>anyList(),
            anyString(),
            org.mockito.ArgumentMatchers.<org.springframework.ai.content.Media>anyList(),
            any(AiProvider.class)))
                .thenReturn("unused");

        ChatService chatService = new ChatService(sessionRepo, aiService, fileStorageService);

        AiChatMessage out = chatService.sendMessage(sessionId, "please compile", null, AiProvider.GEMINI);

        assertNull(out, "Chat turn should terminate with null when authorization is required");

        AiSession saved = sessionRepo.get(sessionId);
        assertNotNull(saved);
        assertEquals("AWAITING_AUTHORIZATION", saved.status());
        assertEquals(2, saved.messages().size(), "Expected persisted USER + AWAITING_AUTHORIZATION messages");

        AiChatMessage user = saved.messages().get(0);
        assertEquals("USER", user.role());
        assertEquals("please compile", user.content());

        AiChatMessage awaiting = saved.messages().get(1);
        assertEquals("AWAITING_AUTHORIZATION", awaiting.role());
        assertTrue(awaiting.content().contains("compileJavaType"));
        assertNotNull(awaiting.toolCalls());
        assertEquals(1, awaiting.toolCalls().size());

        AiChatMessage.ToolCallRef tool = awaiting.toolCalls().get(0);
        assertEquals("FUNCTION", tool.type());
        assertEquals("compileJavaType", tool.name());
        assertEquals("{\"source\":\"class Demo {}\"}", tool.arguments());
        assertTrue(tool.id().startsWith("pending-"));

        assertNull(awaiting.toolCallId());
        assertNull(awaiting.toolName());
    }
}
