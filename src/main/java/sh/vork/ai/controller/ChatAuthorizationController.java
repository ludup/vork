package sh.vork.ai.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.security.AuthorizationRuleEngine;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.database.DatabaseRepository;

/**
 * Accepts structured user responses to PROMPT_REQUIRED frames and resumes chat execution.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatAuthorizationController {

    private static final Logger log = LoggerFactory.getLogger(ChatAuthorizationController.class);

    private final DatabaseRepository<AiSession> sessionRepo;
    private final AuthorizationRuleEngine authorizationRuleEngine;
    private final AiOrchestrationService aiService;
    private final SimpMessagingTemplate messaging;
    private final ObjectMapper objectMapper;
    private final Map<String, ToolCallback> toolCallbacksByName;

    public ChatAuthorizationController(DatabaseRepository<AiSession> sessionRepo,
                                       AuthorizationRuleEngine authorizationRuleEngine,
                                       AiOrchestrationService aiService,
                                       SimpMessagingTemplate messaging,
                                           ObjectMapper objectMapper,
                                           List<ToolCallback> toolCallbacks) {
        this.sessionRepo = sessionRepo;
        this.authorizationRuleEngine = authorizationRuleEngine;
        this.aiService = aiService;
        this.messaging = messaging;
        this.objectMapper = objectMapper;
                        this.toolCallbacksByName = toolCallbacks.stream().collect(
                            java.util.stream.Collectors.toMap(
                                t -> t.getToolDefinition().name(),
                                Function.identity(),
                                (a, b) -> a));
    }

    @PostMapping("/respond/{sessionUuid}")
    public UiEventFrame respond(@PathVariable String sessionUuid,
                                @RequestBody AuthorizationResponseRequest request) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            throw new IllegalStateException("AI session not found: " + sessionUuid);
        }
        if (!"AWAITING_INPUT".equals(session.status())) {
            throw new IllegalStateException("Session is not awaiting input: " + sessionUuid);
        }

        AiChatMessage promptMessage = findPromptMessage(session.messages(), request.eventId());
        UiEventFrame promptEvent = readEventFrame(promptMessage.content());
        String correlationEventId = (request.eventId() == null || request.eventId().isBlank())
            ? promptEvent.eventId()
            : request.eventId();

        try (MDC.MDCCloseable sid = MDC.putCloseable("sessionUuid", sessionUuid);
             MDC.MDCCloseable eid = MDC.putCloseable("eventId", correlationEventId)) {

            String toolName = String.valueOf(promptEvent.payload().get("toolName"));
            String toolCallId = String.valueOf(promptEvent.payload().get("toolCallId"));
            String action = normalizeAction(request.action());
            String username = resolveUsername();
            Map<String, Object> fields = request.fields() == null ? Map.of() : request.fields();

            log.info("Resume request received [action={}, tool={}, toolCallId={}, user={}, fieldKeys={}]",
                action,
                toolName,
                toolCallId,
                username,
                fields.keySet());
            log.debug("Resume request fields payload: {}", abbreviate(toJson(fields), 2000));

            applyAuthorizationAction(action, username, toolName, toolCallId);

            String argumentsJson = String.valueOf(promptEvent.payload().getOrDefault("arguments", "{}"));
            String token = toolResponseDataForAction(action, fields, toolName, argumentsJson);
            ToolResponseMessage.ToolResponse toolResponse =
                new ToolResponseMessage.ToolResponse(toolCallId, toolName, token);
            ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(List.of(toolResponse))
                .metadata(Collections.emptyMap())
                .build();

            String toolPayloadJson = toJson(Map.of(
                "type", "TOOL_RESPONSE",
                "responses", List.of(Map.of(
                    "id", toolResponse.id(),
                    "name", toolResponse.name(),
                    "responseData", toolResponse.responseData())),
                "message", toolResponseMessage.toString(),
                "fields", fields
            ));

            List<AiChatMessage> updated = new ArrayList<>(session.messages());
            updated.add(new AiChatMessage(
                UUID.randomUUID().toString(),
                "TOOL",
                toolPayloadJson,
                System.currentTimeMillis(),
                null,
                null,
                toolCallId,
                toolName));

            log.info("Tool response persisted [tool={}, toolCallId={}, payloadSize={}]",
                toolName, toolCallId, toolPayloadJson.length());
            log.debug("Tool response payload: {}", abbreviate(toolPayloadJson, 4000));

            List<Message> history = hydrateHistory(updated);
            log.info("Resuming model call [historyMessages={}]", history.size());
            String finalText = aiService.generateWithHistory(history, "Please continue based on the tool response.",
                resolveProvider(session.provider()));

            UiEventFrame textEvent = new UiEventFrame(
                UUID.randomUUID().toString(),
                "TEXT_RESPONSE",
                "CHAT_OUTPUT",
                Map.of("content", finalText == null ? "" : finalText));

            updated.add(new AiChatMessage(
                UUID.randomUUID().toString(),
                "TEXT_RESPONSE",
                toJson(textEvent),
                System.currentTimeMillis(),
                null,
                null,
                null,
                null));

            sessionRepo.save(new AiSession(
                session.uuid(),
                session.provider(),
                session.createdAt(),
                List.copyOf(updated),
                null));

            log.info("Resumption completed [finalTextLength={}]", finalText == null ? 0 : finalText.length());
            log.debug("Resumption final text: {}", abbreviate(finalText == null ? "" : finalText, 4000));

            messaging.convertAndSend("/topic/chat/" + sessionUuid, textEvent);
            return textEvent;
        }
    }

    private AiChatMessage findPromptMessage(List<AiChatMessage> messages, String eventId) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiChatMessage message = messages.get(i);
            if (!"PROMPT_REQUIRED".equals(message.role())) {
                continue;
            }
            UiEventFrame frame = readEventFrame(message.content());
            if (eventId == null || eventId.isBlank() || eventId.equals(frame.eventId())) {
                return message;
            }
        }
        throw new IllegalStateException("No matching suspended prompt found");
    }

    private UiEventFrame readEventFrame(String json) {
        try {
            return objectMapper.readValue(json, UiEventFrame.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize UiEventFrame", e);
        }
    }

    private List<Message> hydrateHistory(List<AiChatMessage> messages) {
        List<Message> history = new ArrayList<>();
        for (AiChatMessage message : messages) {
            switch (message.role()) {
                case "USER" -> history.add(new UserMessage(message.content() == null ? "" : message.content()));
                case "ASSISTANT" -> history.add(new AssistantMessage(message.content() == null ? "" : message.content()));
                case "TOOL" -> history.add(toToolResponseMessage(message));
                default -> {
                    // Skip non-conversation event frames and internal control records.
                }
            }
        }
        return history;
    }

    private Message toToolResponseMessage(AiChatMessage message) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(message.content(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            payload = new HashMap<>();
        }

        String responseData = null;
        Object responsesRaw = payload.get("responses");
        if (responsesRaw instanceof List<?> responses && !responses.isEmpty() && responses.get(0) instanceof Map<?, ?> first) {
            Object v = first.get("responseData");
            responseData = v == null ? null : String.valueOf(v);
        }
        if (responseData == null) {
            responseData = message.content();
        }
        responseData = normalizeToolResponseData(responseData);

        ToolResponseMessage.ToolResponse toolResponse = new ToolResponseMessage.ToolResponse(
                message.toolCallId() == null ? "pending-unknown" : message.toolCallId(),
                message.toolName() == null ? "unknown-tool" : message.toolName(),
                responseData);

        return ToolResponseMessage.builder()
                .responses(List.of(toolResponse))
                .metadata(Collections.emptyMap())
                .build();
    }

    private void applyAuthorizationAction(String action,
                                          String username,
                                          String toolName,
                                          String toolCallId) {
        switch (action) {
            case "ONCE", "ALLOW_ONCE" -> {
                // SecuredToolCallback currently uses a synthetic fixed call ID.
                authorizationRuleEngine.addUseOnceRule("pending-id");
                authorizationRuleEngine.addUseOnceRule(toolCallId);
            }
            case "SESSION", "ALLOW_SESSION" -> authorizationRuleEngine.addTemporaryUserRule(username, toolName);
            case "ALWAYS", "ALLOW_ALWAYS" -> authorizationRuleEngine.addPermanentRule(username, toolName);
            case "DENIED", "DENY" -> {
                // No bypass rule written.
            }
            default -> throw new IllegalArgumentException("Unsupported action: " + action);
        }
    }

    private String toolResponseDataForAction(String action,
                                             Map<String, Object> fields,
                                             String toolName,
                                             String argumentsJson) {
        switch (action) {
            case "ONCE", "ALLOW_ONCE", "SESSION", "ALLOW_SESSION", "ALWAYS", "ALLOW_ALWAYS" -> {
                String toolResult = executeTool(toolName, argumentsJson);
                // Prefer raw tool JSON if the tool already returns a JSON object.
                try {
                    objectMapper.readValue(toolResult, new TypeReference<Map<String, Object>>() {});
                    return toolResult;
                } catch (Exception ignored) {
                    return toJson(Map.of(
                            "status", "APPROVED",
                            "action", action,
                            "fields", fields,
                            "result", toolResult
                    ));
                }
            }
            case "DENIED", "DENY" -> {
                return toJson(Map.of(
                        "status", "DENIED",
                        "action", action,
                        "fields", fields,
                        "message", "User denied tool execution"
                ));
            }
            default -> {
                return toJson(Map.of(
                        "status", "UNKNOWN",
                        "action", action,
                        "fields", fields
                ));
            }
        }
    }

    private String executeTool(String toolName, String argumentsJson) {
        ToolCallback callback = toolCallbacksByName.get(toolName);
        if (callback == null) {
            throw new IllegalStateException("No tool callback registered for: " + toolName);
        }
        log.info("Executing approved tool [tool={}, argsSize={}]", toolName,
                argumentsJson == null ? 0 : argumentsJson.length());
        log.debug("Tool arguments [tool={}]: {}", toolName, abbreviate(argumentsJson, 4000));

        String result = callback.call(argumentsJson);

        log.info("Tool execution completed [tool={}, resultSize={}]", toolName,
                result == null ? 0 : result.length());
        log.debug("Tool result [tool={}]: {}", toolName, abbreviate(result, 4000));
        return result;
    }

    private String normalizeToolResponseData(String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return toJson(Map.of("status", "UNKNOWN", "value", ""));
        }
        try {
            objectMapper.readValue(responseData, new TypeReference<Map<String, Object>>() {});
            return responseData; // already JSON object
        } catch (Exception ignored) {
            return toJson(Map.of("status", "LEGACY", "value", responseData));
        }
    }

    private static String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "DENIED";
        }
        return action.trim().toUpperCase(Locale.ROOT);
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "anonymous";
        }
        return auth.getName();
    }

    private static AiProvider resolveProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return AiProvider.GEMINI;
        }
        try {
            return AiProvider.valueOf(provider.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AiProvider.GEMINI;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON payload", e);
        }
    }

    public record AuthorizationResponseRequest(
            String eventId,
            String action,
            Map<String, Object> fields
    ) {}

    private static String abbreviate(String value, int maxLen) {
        if (value == null) {
            return "<null>";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen) + "...<truncated>";
    }
}
