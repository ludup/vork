package sh.vork.ai.controller;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.service.ChatService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles both HTTP session initialisation and WebSocket chat messages.
 *
 * <h3>HTTP</h3>
 * {@code GET /api/chat/session} — called on page load.  Returns the session UUID
 * and full message history so the browser can render prior turns.
 *
 * <h3>WebSocket / STOMP</h3>
 * Client sends to {@code /app/chat.send}; the server broadcasts the AI response
 * to {@code /topic/chat/{sessionUuid}}.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService            chatService;
    private final SimpMessagingTemplate  messaging;

    public ChatController(ChatService chatService, SimpMessagingTemplate messaging) {
        this.chatService = chatService;
        this.messaging   = messaging;
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    @GetMapping("/session")
    public SessionResponse getSession(
            HttpSession httpSession,
            @RequestParam(defaultValue = "GEMINI") AiProvider provider) {
        AiSession session = chatService.getOrCreateSession(httpSession.getId(), provider);
        return new SessionResponse(session.uuid(), session.messages());
    }

    // ── WebSocket / STOMP ─────────────────────────────────────────────────────

    @MessageMapping("/chat.send")
    public void handleChatMessage(ChatRequest request) {
        String sid = request == null ? null : request.sessionUuid();
        try (MDC.MDCCloseable sidCtx = MDC.putCloseable("sessionUuid", sid == null ? "<null>" : sid)) {
            log.debug("WebSocket message received [length={}, attachments={}]",
                request.content() == null ? 0 : request.content().length(),
                request.attachmentUuids() == null ? 0 : request.attachmentUuids().size());
            try {
            AiProvider provider = resolveProvider(request.provider());
            AiChatMessage response = chatService.sendMessage(
                request.sessionUuid(), request.content(), request.attachmentUuids(), provider);
            if (response != null) {
                UiEventFrame frame = new UiEventFrame(
                    UUID.randomUUID().toString(),
                    "TEXT_RESPONSE",
                    "CHAT_OUTPUT",
                    Map.of("message", response));
                messaging.convertAndSend("/topic/chat/" + request.sessionUuid(), frame);
            }
            } catch (Exception ex) {
            log.error("Chat error: {}", ex.getMessage(), ex);
            UiEventFrame frame = new UiEventFrame(
                UUID.randomUUID().toString(),
                "ERROR",
                "CHAT_ERROR",
                Map.of("message", "Sorry, something went wrong: " + ex.getMessage()));
            messaging.convertAndSend("/topic/chat/" + request.sessionUuid(), frame);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AiProvider resolveProvider(String name) {
        if (name == null || name.isBlank()) return AiProvider.GEMINI;
        try {
            return AiProvider.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown provider '{}', defaulting to GEMINI", name);
            return AiProvider.GEMINI;
        }
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record SessionResponse(String sessionUuid, List<AiChatMessage> messages) {}

    record ChatRequest(String sessionUuid, String content, String provider, List<String> attachmentUuids) {}
}
