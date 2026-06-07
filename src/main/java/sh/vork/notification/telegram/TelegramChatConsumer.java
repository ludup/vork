package sh.vork.notification.telegram;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.SearchQuery;
import com.jadaptive.orm.SortOrder;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.service.ChatService;
import sh.vork.ai.telegram.TelegramChatResumptionService;
import sh.vork.ai.telegram.TelegramSessionRegistry;
import sh.vork.ai.telegram.TelegramSuspensionRenderer;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.user.UserNotificationMedia;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Catch-all Telegram message consumer that routes incoming chat text and callback
 * queries to AI sessions.
 *
 * <h3>Message routing</h3>
 * <ul>
 *   <li><b>/new</b> — resets the active session so the next message starts fresh.</li>
 *   <li><b>Callback query</b> ({@code r:{sessionUuid}:{X}}) — resumes an AWAITING_INPUT
 *       session with the selected action and no field values.</li>
 *   <li><b>Pending field capture</b> — if the previous render was SINGLE_TEXT, the text
 *       is treated as the field value and the suspended session is resumed.</li>
 *   <li><b>Normal text</b> — forwarded to the AI session via
 *       {@link ChatService#sendMessageAsUser}.</li>
 * </ul>
 *
 * <p>Runs with {@link Order}(10) — after {@link TelegramRegistrationConsumer} (Order 1)
 * so registration flows are handled first.
 */
@Component
@Order(10)
public class TelegramChatConsumer implements TelegramMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelegramChatConsumer.class);

    /** chatId -> pending capture context (set after SINGLE_TEXT renders) */
    private final ConcurrentHashMap<String, PendingCapture> pendingCaptures = new ConcurrentHashMap<>();

    private final ChatService                              chatService;
    private final DatabaseRepository<AiSession>           sessionRepo;
    private final DatabaseRepository<UserNotificationMedia> mediaRepo;
    private final TelegramSessionRegistry                  sessionRegistry;
    private final TelegramChatResumptionService            resumptionService;
    private final TelegramSuspensionRenderer               suspensionRenderer;
    private final TelegramApiClient                        telegramApiClient;
    private final ObjectMapper                             objectMapper;

    public TelegramChatConsumer(ChatService chatService,
                                 DatabaseRepository<AiSession> sessionRepo,
                                 DatabaseRepository<UserNotificationMedia> mediaRepo,
                                 TelegramSessionRegistry sessionRegistry,
                                 TelegramChatResumptionService resumptionService,
                                 TelegramSuspensionRenderer suspensionRenderer,
                                 TelegramApiClient telegramApiClient,
                                 ObjectMapper objectMapper) {
        this.chatService        = chatService;
        this.sessionRepo        = sessionRepo;
        this.mediaRepo          = mediaRepo;
        this.sessionRegistry    = sessionRegistry;
        this.resumptionService  = resumptionService;
        this.suspensionRenderer = suspensionRenderer;
        this.telegramApiClient  = telegramApiClient;
        this.objectMapper       = objectMapper;
    }

    // ── TelegramMessageConsumer ───────────────────────────────────────────────

    @Override
    public boolean accepts(TelegramMessageConsumer.IncomingMessage message) {
        // Accept everything — runs last after all more-specific consumers
        return true;
    }

    @Override
    public void process(TelegramMessageConsumer.IncomingMessage message) {
        String chatId    = message.chatId();
        String botToken  = message.botToken();
        String configId  = message.configId();

        log.debug("ENTER TelegramChatConsumer.process: chatId={}, callback={}", chatId, message.isCallback());

        try {
            if (message.isCallback()) {
                handleCallback(message);
            } else {
                handleText(message, configId);
            }
        } catch (Exception e) {
            log.warn("Error processing Telegram message [chatId={}]: {}", chatId, e.getMessage(), e);
            telegramApiClient.sendText(botToken, chatId,
                    "Sorry, an error occurred. Please try again.");
        }
    }

    // ── Private: text-message handling ───────────────────────────────────────

    private void handleText(TelegramMessageConsumer.IncomingMessage message, String configId) {
        String chatId   = message.chatId();
        String botToken = message.botToken();
        String text     = message.text();

        if (text == null || text.isBlank()) {
            log.debug("Ignoring empty text from chatId={}", chatId);
            return;
        }

        // /new — reset session
        if ("/new".equalsIgnoreCase(text.trim())) {
            sessionRegistry.reset(chatId);
            pendingCaptures.remove(chatId);
            telegramApiClient.sendText(botToken, chatId,
                    "New session started. How can I help?");
            return;
        }

        // Pending single-field capture?
        PendingCapture capture = pendingCaptures.remove(chatId);
        if (capture != null) {
            handleFieldCapture(capture, text, chatId, botToken);
            return;
        }

        // Normal message — look up user by chatId
        String username = resolveUsername(chatId, botToken);
        if (username == null) return; // user not registered — message already sent

        String sessionUuid = sessionRegistry.getOrCreate(username, configId, chatId, botToken);

        log.debug("Sending Telegram message to session [sessionUuid={}, user={}]", sessionUuid, username);

        AiChatMessage response = chatService.sendMessageAsUser(
                username, sessionUuid, text, List.of(), null);

        if (response == null) {
            // Session suspended — render the suspension prompt
            renderLatestSuspension(sessionUuid, chatId, botToken);
        } else {
            String reply = response.content();
            if (reply != null && !reply.isBlank()) {
                telegramApiClient.sendText(botToken, chatId, reply);
            }
        }
    }

    private void handleFieldCapture(PendingCapture capture, String fieldValue,
                                     String chatId, String botToken) {
        log.debug("Field capture [session={}, event={}, field={}]",
                capture.sessionUuid(), capture.eventId(), capture.fieldName());
        try {
            String result = resumptionService.resumeAndRun(
                    capture.username(), capture.sessionUuid(), capture.eventId(),
                    "ONCE", Map.of(capture.fieldName(), fieldValue));
            if (result != null && !result.isBlank()) {
                telegramApiClient.sendText(botToken, chatId, result);
            }
        } catch (ToolSuspensionException ex) {
            // Suspended again — render the new prompt
            renderLatestSuspension(capture.sessionUuid(), chatId, botToken);
        }
    }

    // ── Private: callback-query handling ─────────────────────────────────────

    private void handleCallback(TelegramMessageConsumer.IncomingMessage message) {
        String callbackData = message.callbackData();
        String chatId       = message.chatId();
        String botToken     = message.botToken();

        String[] decoded = TelegramSuspensionRenderer.decodeCallback(callbackData);
        if (decoded == null) {
            log.warn("Unrecognised Telegram callback_data: {}", callbackData);
            return;
        }
        String sessionUuid = decoded[0];
        String action      = decoded[1];

        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null || session.status() != AiSessionStatus.AWAITING_INPUT) {
            log.debug("Stale callback ignored — session not awaiting input [session={}, status={}]",
                    sessionUuid, session == null ? "null" : session.status());
            return;
        }

        log.debug("Telegram callback resume [session={}, action={}]", sessionUuid, action);
        try {
            String result = resumptionService.resumeAndRun(
                    session.username(), sessionUuid, null, action, Map.of());
            if (result != null && !result.isBlank()) {
                telegramApiClient.sendText(botToken, chatId, result);
            }
        } catch (ToolSuspensionException ex) {
            renderLatestSuspension(sessionUuid, chatId, botToken);
        }
    }

    // ── Private: suspension rendering ────────────────────────────────────────

    private void renderLatestSuspension(String sessionUuid, String chatId, String botToken) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) return;

        UiEventFrame promptEvent = findLatestPromptEvent(session);
        if (promptEvent == null) {
            log.warn("No PROMPT_REQUIRED message in suspended session [session={}]", sessionUuid);
            telegramApiClient.sendText(botToken, chatId,
                    "An action is required. Please use the Vork web app.");
            return;
        }

        TelegramSuspensionRenderer.FormClass formClass =
                suspensionRenderer.render(chatId, botToken, session, promptEvent);

        if (formClass == TelegramSuspensionRenderer.FormClass.SINGLE_TEXT) {
            // Store a pending capture so the user's next text is the field value
            String fieldName = findSingleVisibleFieldName(promptEvent);
            pendingCaptures.put(chatId,
                    new PendingCapture(session.username(), sessionUuid,
                            promptEvent.eventId(), fieldName));
        }
    }

    // ── Private: helpers ──────────────────────────────────────────────────────

    /**
     * Resolves the Vork username for a Telegram chatId by looking up
     * {@link UserNotificationMedia} records with {@code address=chatId} and
     * {@code mediaType=TELEGRAM}.  Sends a friendly message if the user is not registered.
     *
     * @return username, or {@code null} if not found
     */
    private String resolveUsername(String chatId, String botToken) {
        try (var stream = mediaRepo.search(0, 10, "createdAt", SortOrder.ASC,
                SearchQuery.eq("address", chatId),
                SearchQuery.eq("mediaType", NotificationMediaType.TELEGRAM.name()))) {

            return stream.map(UserNotificationMedia::userId).findFirst().orElseGet(() -> {
                log.warn("Telegram chatId {} not linked to any Vork account", chatId);
                telegramApiClient.sendText(botToken, chatId,
                        "Your Telegram account isn't linked to Vork yet. "
                                + "Please use /start <code> in this chat (the code is shown in your Vork profile).");
                return null;
            });
        }
    }

    private UiEventFrame findLatestPromptEvent(AiSession session) {
        List<AiChatMessage> msgs = session.messages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            AiChatMessage m = msgs.get(i);
            if ("PROMPT_REQUIRED".equals(m.role())) {
                try {
                    return objectMapper.readValue(m.content(), UiEventFrame.class);
                } catch (Exception e) {
                    log.warn("Could not parse PROMPT_REQUIRED content [session={}]: {}", session.uuid(), e.getMessage());
                }
            }
        }
        return null;
    }

    private String findSingleVisibleFieldName(UiEventFrame promptEvent) {
        if (promptEvent.formSchema() == null) return "value";
        if (promptEvent.formSchema().fields() == null) return "value";
        return promptEvent.formSchema().fields().stream()
                .filter(f -> f != null && !isInvisibleType(f.type()))
                .map(sh.vork.ai.protocol.interaction.FormField::name)
                .findFirst().orElse("value");
    }

    private static boolean isInvisibleType(String type) {
        if (type == null) return false;
        String t = type.toUpperCase();
        return "HIDDEN".equals(t) || "MARKDOWN".equals(t);
    }

    // ── Value types ───────────────────────────────────────────────────────────

    private record PendingCapture(String username, String sessionUuid, String eventId, String fieldName) {}
}
