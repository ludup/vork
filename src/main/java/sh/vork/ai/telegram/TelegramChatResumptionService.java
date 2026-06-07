package sh.vork.ai.telegram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.protocol.StructuredAgentResponse;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.security.AuthorizationRuleEngine;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.ai.service.ChatService;
import sh.vork.scheduling.service.SystemBackgroundAuthentication;
import com.jadaptive.orm.DatabaseRepository;
import sh.vork.security.SecureCredentialStore;
import sh.vork.security.VorkUser;

/**
 * Telegram-channel counterpart of the web-layer ChatAuthorizationController resume flow.
 *
 * <p>Accepts an already-validated username, session UUID, event ID, action code, and
 * field values; processes them exactly as the web controller does; and returns the
 * final assistant response text so the caller can forward it to the Telegram chat.
 *
 * <p>On re-suspension (the resumed tool triggers another ToolSuspensionException) the
 * new AWAITING_INPUT state is saved to the session repository and a
 * {@link ToolSuspensionException} is re-thrown so the caller can render the next prompt.
 */
@Service
public class TelegramChatResumptionService {

    private static final Logger log = LoggerFactory.getLogger(TelegramChatResumptionService.class);
    private static final int    MAX_RESUME_ITERATIONS = 10;

    private final DatabaseRepository<AiSession>  sessionRepo;
    private final SessionEnvironmentService       sessionEnvironmentService;
    private final SecureCredentialStore           secureCredentialStore;
    private final AuthorizationRuleEngine         authorizationRuleEngine;
    private final AiOrchestrationService          aiService;
    private final ChatService                     chatService;
    private final ObjectMapper                    objectMapper;
    private final Map<String, ToolCallback>       toolCallbacksByName;

    public TelegramChatResumptionService(DatabaseRepository<AiSession> sessionRepo,
                                          SessionEnvironmentService sessionEnvironmentService,
                                          SecureCredentialStore secureCredentialStore,
                                          AuthorizationRuleEngine authorizationRuleEngine,
                                          AiOrchestrationService aiService,
                                          ChatService chatService,
                                          ObjectMapper objectMapper,
                                          List<ToolCallback> toolCallbacks) {
        this.sessionRepo              = sessionRepo;
        this.sessionEnvironmentService = sessionEnvironmentService;
        this.secureCredentialStore    = secureCredentialStore;
        this.authorizationRuleEngine  = authorizationRuleEngine;
        this.aiService                = aiService;
        this.chatService              = chatService;
        this.objectMapper             = objectMapper;
        this.toolCallbacksByName      = toolCallbacks.stream().collect(
                Collectors.toMap(t -> t.getToolDefinition().name(), Function.identity(), (a, b) -> a));
    }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Resumes a suspended tool call on behalf of the specified user.
     *
     * @param username    authenticated Telegram user (session owner)
     * @param sessionUuid UUID of the AWAITING_INPUT session
     * @param eventId     event ID of the PROMPT_REQUIRED message (may be null to pick latest)
     * @param action      action code: ONCE, SESSION, ALWAYS, or DENIED
     * @param fields      form field values keyed by field name
     * @return final assistant response text
     * @throws ToolSuspensionException if the resumed flow suspends again (new AWAITING_INPUT
     *                                 state already saved; caller should render the new prompt)
     */
    public String resumeAndRun(String username, String sessionUuid, String eventId,
                                String action, Map<String, String> fields)
            throws ToolSuspensionException {

        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            throw new IllegalStateException("AI session not found: " + sessionUuid);
        }
        if (session.status() != AiSessionStatus.AWAITING_INPUT) {
            throw new IllegalStateException("Session is not awaiting input: " + sessionUuid);
        }

        ToolExecutionContext.bindSessionUuid(sessionUuid);
        ToolExecutionContext.hydrate(session.environmentVariables());

        // Ensure tools running on non-web threads (Telegram, background) can resolve the principal
        SecurityContext prevCtx = SecurityContextHolder.getContext();
        boolean needsCtx = prevCtx.getAuthentication() == null
                || prevCtx.getAuthentication().getName() == null
                || prevCtx.getAuthentication().getName().isBlank();
        if (needsCtx) {
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new SystemBackgroundAuthentication(username));
            SecurityContextHolder.setContext(ctx);
        }

        AiChatMessage promptMessage = findPromptMessage(session.messages(), eventId);
        UiEventFrame promptEvent    = readEventFrame(promptMessage.content());
        String correlationEventId   = (eventId == null || eventId.isBlank())
                ? promptEvent.eventId() : eventId;

        try (MDC.MDCCloseable _ = MDC.putCloseable("sessionUuid", sessionUuid);
             MDC.MDCCloseable _ = MDC.putCloseable("eventId", correlationEventId)) {

            String normalizedAction = normalizeAction(action);
            String toolName         = promptMessage.toolName();
            String toolCallId       = promptMessage.toolCallId();
            String argumentsJson    = extractPendingArguments(promptMessage, toolCallId);

            // ── Dispatch field values by FieldSource ──────────────────────────
            Map<String, FieldSource> sourceByField = buildFieldSourceMap(promptEvent.formSchema());
            Map<String, String>      conversationFields = new HashMap<>();
            for (Map.Entry<String, String> entry : (fields == null ? Map.<String, String>of() : fields).entrySet()) {
                String key   = entry.getKey();
                String value = entry.getValue();
                FieldSource source = sourceByField.getOrDefault(key, FieldSource.CONVERSATION);
                switch (source) {
                    case SECRET -> secureCredentialStore.saveSecret(
                            new VorkUser(username, "", "USER", 0L, 0L), key, value);
                    case CONTEXT -> {
                        sessionEnvironmentService.setEnv(sessionUuid, key, value);
                        ToolExecutionContext.put(key, value);
                    }
                    case CONVERSATION -> conversationFields.put(key, value);
                }
            }

            // For HOST_KEY_VERIFICATION and similar HIDDEN CONTEXT fields that the web UI
            // submits automatically, we collect them from the schema's placeholder values.
            if (promptEvent.formSchema() != null && promptEvent.formSchema().fields() != null) {
                for (FormField field : promptEvent.formSchema().fields()) {
                    if ("HIDDEN".equalsIgnoreCase(field.type()) && field.source() == FieldSource.CONTEXT
                            && !conversationFields.containsKey(field.name())
                            && (fields == null || !fields.containsKey(field.name()))) {
                        String value = field.placeholder() != null ? field.placeholder() : "";
                        sessionEnvironmentService.setEnv(sessionUuid, field.name(), value);
                        ToolExecutionContext.put(field.name(), value);
                    }
                }
            }

            log.info("Telegram resume [action={}, tool={}, toolCallId={}, user={}]",
                    normalizedAction, toolName, toolCallId, username);

            // ── Apply authorization rule ───────────────────────────────────────
            applyAuthorizationAction(normalizedAction, username, toolName, toolCallId);

            // ── Execute tool ──────────────────────────────────────────────────
            String token;
            try {
                token = toolResponseDataForAction(normalizedAction, conversationFields,
                        toolName, argumentsJson);
            } catch (ToolSuspensionException ex) {
                // Tool suspended again before producing a result
                return saveAndRethrow(session, ex, toolCallId, argumentsJson);
            }

            // ── Build TOOL message ────────────────────────────────────────────
            ToolResponseMessage.ToolResponse toolResponse =
                    new ToolResponseMessage.ToolResponse(toolCallId, toolName, token);

            String toolPayloadJson = toJson(Map.of(
                    "type",      "TOOL_RESPONSE",
                    "responses", List.of(Map.of("id", toolResponse.id(),
                            "name", toolResponse.name(),
                            "responseData", toolResponse.responseData())),
                    "fields",    conversationFields));

            AiChatMessage toolMessage = new AiChatMessage(
                    UUID.randomUUID().toString(), "TOOL", toolPayloadJson,
                    System.currentTimeMillis(), null, null, toolCallId, toolName);

            List<AiChatMessage> updated = new ArrayList<>(session.messages());
            updated.add(toolMessage);

            // Persist immediately (so a model failure cannot drop the tool result)
            sessionRepo.save(withMessages(session, updated, AiSessionStatus.RUNNING));

            // ── Run AI continuation loop ───────────────────────────────────────
            List<Message> history = hydrateHistory(updated);
            String finalText = null;
            ToolExecutionContext.bindSessionUuid(sessionUuid);
            ToolExecutionContext.hydrate(session.environmentVariables());
            try {
                String continuationPrompt = "DENIED".equals(normalizedAction)
                        ? "The tool call was denied. Explain to the user why you cannot proceed."
                        : "The tool result is in the conversation history. Summarize it for the user.";

                for (int iter = 0; iter < MAX_RESUME_ITERATIONS; iter++) {
                    String rawResponse = aiService.generateWithHistory(
                            history, continuationPrompt, resolveProvider(session.provider()));
                    StructuredAgentResponse structured = extractStructured(rawResponse);

                    if ("CONTINUE_TURN".equals(structured.status())) {
                        String progressText = structured.textResponse() != null
                                && !structured.textResponse().isBlank()
                                ? structured.textResponse() : "";
                        if (!progressText.isBlank()) {
                            updated.add(new AiChatMessage(UUID.randomUUID().toString(), "ASSISTANT",
                                    progressText, System.currentTimeMillis(), null, null, null, null));
                        }
                        history.add(new AssistantMessage(progressText.isBlank() ? rawResponse : progressText));
                        continuationPrompt = "Continue executing the task.";
                    } else if ("DELEGATE_TURN".equals(structured.status())
                            || "SWITCH_AGENT".equals(structured.status())) {
                        if (structured.targetAgent() != null) {
                            chatService.switchActiveAgentByName(sessionUuid, structured.targetAgent());
                        }
                        finalText = structured.textResponse() != null && !structured.textResponse().isBlank()
                                ? structured.textResponse() : rawResponse;
                        break;
                    } else {
                        finalText = structured.textResponse() != null && !structured.textResponse().isBlank()
                                ? structured.textResponse() : rawResponse;
                        break;
                    }
                }
                if (finalText == null) {
                    finalText = "Processing required too many steps and was interrupted.";
                }
            } catch (ToolSuspensionException ex) {
                return saveAndRethrow(session, ex, toolCallId, argumentsJson);
            } catch (Exception ex) {
                log.error("AI continuation failed after Telegram resume [session={}]: {}",
                        sessionUuid, ex.getMessage(), ex);
                finalText = "Something went wrong while processing the result. Please try again.";
            }

            updated.add(new AiChatMessage(UUID.randomUUID().toString(), "TEXT_RESPONSE",
                    toJson(new UiEventFrame(UUID.randomUUID().toString(), "TEXT_RESPONSE",
                            "CHAT_OUTPUT", finalText, null)),
                    System.currentTimeMillis(), null, null, null, null));

            sessionRepo.save(withMessages(session, updated, AiSessionStatus.RUNNING));
            authorizationRuleEngine.removeUseOnceRule("pending-id");
            chatService.maybeGenerateSessionName(sessionUuid);

            log.info("Telegram resumption completed [session={}, responseLength={}]",
                    sessionUuid, finalText.length());
            return finalText;

        } finally {
            ToolExecutionContext.clear();
            if (needsCtx) {
                SecurityContextHolder.setContext(prevCtx);
            }
        }
    }

    /**
     * Processes form fields and executes the authorized/denied tool, then saves the
     * session with {@code RUNNING} status — but does <em>NOT</em> run the AI continuation
     * loop.  Intended for background-process resumptions where the caller will hand off
     * to {@code AiSchedulerService.resumeBackgroundSession()} on its own thread.
     *
     * @param username    authenticated user (session owner)
     * @param sessionUuid UUID of the AWAITING_INPUT session
     * @param eventId     event ID of the PROMPT_REQUIRED message (null = pick latest)
     * @param action      ONCE / SESSION / ALWAYS / DENIED
     * @param fields      form field values keyed by field name
     * @throws ToolSuspensionException if the tool immediately suspends again
     */
    public void processAndActivate(String username, String sessionUuid, String eventId,
                                    String action, Map<String, String> fields)
            throws ToolSuspensionException {

        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            throw new IllegalStateException("AI session not found: " + sessionUuid);
        }
        if (session.status() != AiSessionStatus.AWAITING_INPUT) {
            throw new IllegalStateException("Session is not awaiting input: " + sessionUuid);
        }

        ToolExecutionContext.bindSessionUuid(sessionUuid);
        ToolExecutionContext.hydrate(session.environmentVariables());

        // Ensure tools running on non-web threads can resolve the principal
        SecurityContext prevCtxPA = SecurityContextHolder.getContext();
        boolean needsCtxPA = prevCtxPA.getAuthentication() == null
                || prevCtxPA.getAuthentication().getName() == null
                || prevCtxPA.getAuthentication().getName().isBlank();
        if (needsCtxPA) {
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new SystemBackgroundAuthentication(username));
            SecurityContextHolder.setContext(ctx);
        }

        AiChatMessage promptMessage = findPromptMessage(session.messages(), eventId);
        UiEventFrame  promptEvent   = readEventFrame(promptMessage.content());
        String correlationEventId   = (eventId == null || eventId.isBlank())
                ? promptEvent.eventId() : eventId;

        try (MDC.MDCCloseable _ = MDC.putCloseable("sessionUuid", sessionUuid);
             MDC.MDCCloseable _ = MDC.putCloseable("eventId", correlationEventId)) {

            String normalizedAction = normalizeAction(action);
            String toolName         = promptMessage.toolName();
            String toolCallId       = promptMessage.toolCallId();
            String argumentsJson    = extractPendingArguments(promptMessage, toolCallId);

            // Dispatch field values by FieldSource
            Map<String, FieldSource> sourceByField    = buildFieldSourceMap(promptEvent.formSchema());
            Map<String, String>      conversationFields = new HashMap<>();
            for (Map.Entry<String, String> entry : (fields == null ? Map.<String, String>of() : fields).entrySet()) {
                String key   = entry.getKey();
                String value = entry.getValue();
                FieldSource source = sourceByField.getOrDefault(key, FieldSource.CONVERSATION);
                switch (source) {
                    case SECRET -> secureCredentialStore.saveSecret(
                            new VorkUser(username, "", "USER", 0L, 0L), key, value);
                    case CONTEXT -> {
                        sessionEnvironmentService.setEnv(sessionUuid, key, value);
                        ToolExecutionContext.put(key, value);
                    }
                    case CONVERSATION -> conversationFields.put(key, value);
                }
            }
            // Auto-inject HIDDEN CONTEXT fields from schema
            if (promptEvent.formSchema() != null && promptEvent.formSchema().fields() != null) {
                for (FormField field : promptEvent.formSchema().fields()) {
                    if ("HIDDEN".equalsIgnoreCase(field.type()) && field.source() == FieldSource.CONTEXT
                            && !conversationFields.containsKey(field.name())
                            && (fields == null || !fields.containsKey(field.name()))) {
                        String value = field.placeholder() != null ? field.placeholder() : "";
                        sessionEnvironmentService.setEnv(sessionUuid, field.name(), value);
                        ToolExecutionContext.put(field.name(), value);
                    }
                }
            }

            log.info("Background form resume [action={}, tool={}, user={}]",
                    normalizedAction, toolName, username);

            applyAuthorizationAction(normalizedAction, username, toolName, toolCallId);

            String token;
            try {
                token = toolResponseDataForAction(normalizedAction, conversationFields,
                        toolName, argumentsJson);
            } catch (ToolSuspensionException ex) {
                saveAndRethrow(session, ex, toolCallId, argumentsJson);
                throw ex; // unreachable, but satisfies compiler
            }

            ToolResponseMessage.ToolResponse toolResponse =
                    new ToolResponseMessage.ToolResponse(toolCallId, toolName, token);
            String toolPayloadJson = toJson(Map.of(
                    "type",      "TOOL_RESPONSE",
                    "responses", List.of(Map.of("id", toolResponse.id(),
                            "name", toolResponse.name(),
                            "responseData", toolResponse.responseData())),
                    "fields",    conversationFields));

            AiChatMessage toolMessage = new AiChatMessage(
                    UUID.randomUUID().toString(), "TOOL", toolPayloadJson,
                    System.currentTimeMillis(), null, null, toolCallId, toolName);

            List<AiChatMessage> updated = new ArrayList<>(session.messages());
            updated.add(toolMessage);

            // Persist with RUNNING so BackgroundOrchestrationEngine can take over
            sessionRepo.save(withMessages(session, updated, AiSessionStatus.RUNNING));
            log.info("Background session activated [session={}, tool={}]", sessionUuid, toolName);

        } finally {
            ToolExecutionContext.clear();
            if (needsCtxPA) {
                SecurityContextHolder.setContext(prevCtxPA);
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Saves a new AWAITING_INPUT state for a re-suspension event and re-throws
     * the exception so the caller can render the new prompt via Telegram.
     */
    private String saveAndRethrow(AiSession session, ToolSuspensionException ex,
                                   String priorToolCallId, String priorArgs) {
        String suspendedCallId = "pending-" + UUID.randomUUID();
        String suspendedArgs   = resolveSuspendedArguments(priorArgs, ex.getArguments());
        String justification   = (ex.getJustification() == null || ex.getJustification().isBlank())
                ? defaultAuthorizationReason(ex.getToolName()) : ex.getJustification();

        UiEventFrame newPrompt = new UiEventFrame(
                UUID.randomUUID().toString(), "PROMPT_REQUIRED", "AUTHORIZE_TOOL",
                justification, ex.getFormSchema());

        List<AiChatMessage> msgs = new ArrayList<>(session.messages());
        msgs.add(new AiChatMessage(UUID.randomUUID().toString(), "PROMPT_REQUIRED",
                toJson(newPrompt), System.currentTimeMillis(), null,
                List.of(new AiChatMessage.ToolCallRef(suspendedCallId, "FUNCTION",
                        ex.getToolName(), suspendedArgs)),
                suspendedCallId, ex.getToolName()));

        sessionRepo.save(withMessages(session, msgs, AiSessionStatus.AWAITING_INPUT));
        log.info("Telegram resume suspended again [tool={}, session={}]",
                ex.getToolName(), session.uuid());
        throw ex; // caller renders the new form
    }

    private AiSession withMessages(AiSession s, List<AiChatMessage> msgs, AiSessionStatus status) {
        return new AiSession(s.uuid(), s.provider(),
                s.originMode() == null ? SessionOriginMode.TELEGRAM : s.originMode(),
                s.username(), s.name(), s.createdAt(), s.currentRoundCount(),
                List.copyOf(msgs), s.environmentVariables(), status,
                s.activeAgentTemplateId(), s.modelId());
    }

    private void applyAuthorizationAction(String action, String username,
                                           String toolName, String toolCallId) {
        switch (action) {
            case "ONCE", "ALLOW_ONCE" -> {
                authorizationRuleEngine.addUseOnceRule("pending-id");
                authorizationRuleEngine.addUseOnceRule(toolCallId);
            }
            case "SESSION", "ALLOW_SESSION" -> authorizationRuleEngine.addTemporaryUserRule(username, toolName);
            case "ALWAYS", "ALLOW_ALWAYS"   -> authorizationRuleEngine.addPermanentRule(username, toolName);
            case "DENIED", "DENY"           -> { /* no bypass */ }
            default -> throw new IllegalArgumentException("Unsupported action: " + action);
        }
    }

    private String toolResponseDataForAction(String action, Map<String, String> fields,
                                              String toolName, String argumentsJson) {
        return switch (action) {
            case "ONCE", "ALLOW_ONCE", "SESSION", "ALLOW_SESSION", "ALWAYS", "ALLOW_ALWAYS" -> {
                String result = executeTool(toolName, argumentsJson);
                try {
                    objectMapper.readValue(result, new TypeReference<Map<String, Object>>() {});
                    yield result;
                } catch (Exception ignored) {
                    yield toJson(Map.of("status", "APPROVED", "action", action,
                            "fields", fields, "result", result));
                }
            }
            case "DENIED", "DENY" -> toJson(Map.of("status", "DENIED", "action", action,
                    "fields", fields, "message", "User denied tool execution"));
            default -> toJson(Map.of("status", "UNKNOWN", "action", action, "fields", fields));
        };
    }

    private String executeTool(String toolName, String argumentsJson) {
        ToolCallback callback = toolCallbacksByName.get(toolName);
        if (callback == null) {
            throw new IllegalStateException("No tool callback for: " + toolName);
        }
        try {
            return callback.call(argumentsJson);
        } catch (RuntimeException ex) {
            ToolSuspensionException suspension = findCause(ex, ToolSuspensionException.class);
            if (suspension != null) throw suspension;
            throw ex;
        }
    }

    private List<Message> hydrateHistory(List<AiChatMessage> messages) {
        List<Message> history = new ArrayList<>();
        for (AiChatMessage m : messages) {
            switch (m.role()) {
                case "USER"      -> history.add(new UserMessage(m.content() == null ? "" : m.content()));
                case "ASSISTANT" -> history.add(new AssistantMessage(m.content() == null ? "" : m.content()));
                case "TOOL"      -> history.add(toToolResponseMessage(m));
                default          -> { /* skip control frames */ }
            }
        }
        return history;
    }

    private Message toToolResponseMessage(AiChatMessage m) {
        String responseData = m.content();
        try {
            Map<String, Object> payload = objectMapper.readValue(m.content(),
                    new TypeReference<Map<String, Object>>() {});
            Object responsesRaw = payload.get("responses");
            if (responsesRaw instanceof List<?> responses && !responses.isEmpty()
                    && responses.get(0) instanceof Map<?, ?> first) {
                Object v = first.get("responseData");
                if (v != null) responseData = String.valueOf(v);
            }
        } catch (Exception ignored) { }

        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        m.toolCallId() == null ? "pending-unknown" : m.toolCallId(),
                        m.toolName()   == null ? "unknown-tool"   : m.toolName(),
                        responseData)))
                .metadata(Collections.emptyMap())
                .build();
    }

    private StructuredAgentResponse extractStructured(String raw) {
        if (raw == null || raw.isBlank()) return new StructuredAgentResponse("FINISHED_TURN", "", null, null);
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                json = json.replaceAll("(?s)^```[a-zA-Z]*\\n?", "").replaceAll("(?s)```\\s*$", "").strip();
            }
            return objectMapper.readValue(json, StructuredAgentResponse.class);
        } catch (Exception ignored) {
            return new StructuredAgentResponse("FINISHED_TURN", raw, null, null);
        }
    }

    private UiEventFrame readEventFrame(String content) {
        try {
            return objectMapper.readValue(content, UiEventFrame.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize UiEventFrame", e);
        }
    }

    private AiChatMessage findPromptMessage(List<AiChatMessage> messages, String eventId) {
        if (eventId != null && !eventId.isBlank()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                AiChatMessage m = messages.get(i);
                if ("PROMPT_REQUIRED".equals(m.role())) {
                    try {
                        UiEventFrame frame = readEventFrame(m.content());
                        if (eventId.equals(frame.eventId())) return m;
                    } catch (Exception ignored) { }
                }
            }
        }
        // Fall back to most-recent PROMPT_REQUIRED
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("PROMPT_REQUIRED".equals(messages.get(i).role())) return messages.get(i);
        }
        throw new IllegalStateException("No PROMPT_REQUIRED message found in session");
    }

    private static String extractPendingArguments(AiChatMessage m, String toolCallId) {
        if (m.toolCalls() == null || m.toolCalls().isEmpty()) return "{}";
        for (AiChatMessage.ToolCallRef call : m.toolCalls()) {
            if (call != null && toolCallId != null && toolCallId.equals(call.id())) {
                return call.arguments() == null ? "{}" : call.arguments();
            }
        }
        AiChatMessage.ToolCallRef first = m.toolCalls().get(0);
        return first.arguments() == null ? "{}" : first.arguments();
    }

    private static Map<String, FieldSource> buildFieldSourceMap(InteractionFormSchema schema) {
        if (schema == null || schema.fields() == null) return Map.of();
        Map<String, FieldSource> map = new HashMap<>();
        for (FormField f : schema.fields()) {
            if (f != null && f.name() != null && !f.name().isBlank()) {
                map.put(f.name(), f.source() == null ? FieldSource.CONVERSATION : f.source());
            }
        }
        return map;
    }

    private static String normalizeAction(String action) {
        if (action == null || action.isBlank()) return "DENIED";
        return action.trim().toUpperCase(Locale.ROOT);
    }

    private static String resolveSuspendedArguments(String prior, String suspended) {
        String fallback = (prior == null || prior.isBlank()) ? "{}" : prior;
        if (suspended == null) return fallback;
        String candidate = suspended.trim();
        return (candidate.isBlank() || "{}".equals(candidate)) ? fallback : suspended;
    }

    private static String defaultAuthorizationReason(String toolName) {
        return "Approval is required to run this protected action so your request can continue safely.";
    }

    private static AiProvider resolveProvider(String provider) {
        if (provider == null || provider.isBlank()) return AiProvider.GEMINI;
        try {
            AiProvider p = AiProvider.valueOf(provider.toUpperCase(Locale.ROOT));
            return p == AiProvider.BACKGROUND_SCHEDULER ? AiProvider.GEMINI : p;
        } catch (IllegalArgumentException e) {
            return AiProvider.GEMINI;
        }
    }

    private static <T extends Throwable> T findCause(Throwable ex, Class<T> type) {
        Throwable current = ex;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getCause();
        }
        return null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }
}
