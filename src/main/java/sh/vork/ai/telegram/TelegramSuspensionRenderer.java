package sh.vork.ai.telegram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jadaptive.orm.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.notification.telegram.TelegramApiClient;
import sh.vork.notification.telegram.TelegramApiClient.InlineButton;
import sh.vork.relay.RelayEncryptionService;
import sh.vork.relay.RelayHttpClient;
import sh.vork.relay.lib.model.RelaySubmission;
import sh.vork.setup.SystemSettingsService;

/**
 * Decides how to render a {@link ToolSuspensionException} prompt for a Telegram user
 * and sends the appropriate message via the bot.
 *
 * <h3>Form classification rules</h3>
 * <ul>
 *   <li><b>SIMPLE</b> – no visible user-input fields (only HIDDEN / MARKDOWN types):
 *       sends an inline keyboard with one button per action.</li>
 *   <li><b>SINGLE_TEXT</b> – exactly one visible, non-password text/select field:
 *       sends a plain-text prompt asking the user to type the value; the consumer
 *       saves a pending-capture entry so the next message is treated as the answer.</li>
 *   <li><b>WEB_FORM</b> – contains a password field or two or more visible fields:
 *       sends a link to the Telegram web-form endpoint (requires appBaseUrl to be set).</li>
 * </ul>
 *
 * <h3>Inline keyboard callback_data format</h3>
 * <pre>{@code r:{sessionUuid}:{X}}</pre>
 * where X is a single char: O=ONCE, S=SESSION, A=ALWAYS, D=DENIED.
 * Total length is {@code 2 + 36 + 1 + 1 = 40 bytes}, well within Telegram's 64-byte limit.
 */
@Service
public class TelegramSuspensionRenderer {

    private static final String DEFAULT_RELAY_BASE_URL = "https://relay.vork.sh";

    private static final Logger log = LoggerFactory.getLogger(TelegramSuspensionRenderer.class);

    private final TelegramApiClient             telegramApiClient;
    private final InputFormTokenService          formTokenService;
    private final SystemSettingsService          systemSettingsService;
    private final RelayEncryptionService         relayEncryption;
    private final RelayHttpClient                relayHttpClient;
    private final TelegramChatResumptionService  resumptionService;
    private final DatabaseRepository<AiSession>  sessionRepo;
    private final ObjectMapper                   objectMapper;

    @Value("${vork.app.base-url:}")
    private String propertyBaseUrl;

    public TelegramSuspensionRenderer(TelegramApiClient telegramApiClient,
                                       InputFormTokenService formTokenService,
                                       SystemSettingsService systemSettingsService,
                                       RelayEncryptionService relayEncryption,
                                       RelayHttpClient relayHttpClient,
                                       TelegramChatResumptionService resumptionService,
                                       DatabaseRepository<AiSession> sessionRepo,
                                       ObjectMapper objectMapper) {
        this.telegramApiClient  = telegramApiClient;
        this.formTokenService   = formTokenService;
        this.systemSettingsService = systemSettingsService;
        this.relayEncryption    = relayEncryption;
        this.relayHttpClient    = relayHttpClient;
        this.resumptionService  = resumptionService;
        this.sessionRepo        = sessionRepo;
        this.objectMapper       = objectMapper;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Renders the pending form prompt to the user's Telegram chat.
     *
     * @param chatId      Telegram chat ID
     * @param botToken    bot token
     * @param session     the AWAITING_INPUT session
     * @param promptEvent the PROMPT_REQUIRED event frame from the latest AWAITING_INPUT message
     * @return the classification used, exposed for the consumer to decide whether to set up
     *         a pending field-capture slot ({@link FormClass#SINGLE_TEXT})
     */
    public FormClass render(String chatId, String botToken, AiSession session,
                             UiEventFrame promptEvent) {
        InteractionFormSchema schema  = promptEvent.formSchema();
        String                heading = promptEvent.textResponse();
        String                title   = schema != null ? schema.title() : "Action required";
        String description = (heading != null && !heading.isBlank()) ? heading
                : (schema != null && schema.description() != null ? schema.description() : "");

        FormClass formClass = classify(schema);
        log.debug("Telegram suspension render [session={}, class={}, title={}]",
                session.uuid(), formClass, title);

        switch (formClass) {
            case SIMPLE      -> renderSimple(chatId, botToken, session, schema, title, description);
            case SINGLE_TEXT -> renderSingleText(chatId, botToken, schema, title, description);
            case WEB_FORM    -> renderWebForm(chatId, botToken, session, promptEvent, title, description);
        }
        return formClass;
    }

    // ── Classification ────────────────────────────────────────────────────────

    public enum FormClass { SIMPLE, SINGLE_TEXT, WEB_FORM }

    private FormClass classify(InteractionFormSchema schema) {
        if (schema == null || schema.fields() == null || schema.fields().isEmpty()) return FormClass.SIMPLE;
        List<FormField> visible = schema.fields().stream()
                .filter(f -> f != null && !isInvisibleType(f.type()))
                .toList();
        if (visible.isEmpty())                        return FormClass.SIMPLE;
        if (visible.size() == 1 && !isPasswordType(visible.get(0).type())) return FormClass.SINGLE_TEXT;
        return FormClass.WEB_FORM;
    }

    private static boolean isInvisibleType(String type) {
        if (type == null) return false;
        String t = type.toUpperCase();
        return "HIDDEN".equals(t) || "MARKDOWN".equals(t);
    }

    private static boolean isPasswordType(String type) {
        return "password".equalsIgnoreCase(type);
    }

    // ── Render strategies ─────────────────────────────────────────────────────

    private void renderSimple(String chatId, String botToken, AiSession session,
                               InteractionFormSchema schema, String title, String description) {
        String codeContent = extractCodeContent(schema);
        String text = buildPromptTextMarkdownV2(title, description, codeContent, null);
        List<InlineButton> row = new ArrayList<>();
        if (schema != null && schema.actions() != null) {
            for (FormAction action : schema.actions()) {
                row.add(new InlineButton(action.label(), encodeCallback(session.uuid(), action.name())));
            }
        }
        telegramApiClient.sendWithInlineKeyboardMarkdownV2(botToken, chatId, text, List.of(row));
    }

    private void renderSingleText(String chatId, String botToken, InteractionFormSchema schema,
                                   String title, String description) {
        FormField field = schema.fields().stream()
                .filter(f -> f != null && !isInvisibleType(f.type()))
                .findFirst().orElseThrow();
        String codeContent = extractCodeContent(schema);
        String suffix = escapeMarkdownV2("\nPlease reply with: " + field.label()
                        + (field.placeholder() != null && !field.placeholder().isBlank()
                                ? " (" + field.placeholder() + ")" : ""));
        String prompt = buildPromptTextMarkdownV2(title, description, codeContent, suffix);
        telegramApiClient.sendTextMarkdownV2(botToken, chatId, prompt);
    }

    private void renderWebForm(String chatId, String botToken, AiSession session,
                                UiEventFrame promptEvent, String title, String description) {
        String codeContent = extractCodeContent(promptEvent.formSchema());

        // If appBaseUrl is explicitly configured, use the self-hosted /input-form endpoint.
        // Only fall back to the zero-knowledge relay when no custom URL is set.
        String configuredUrl = resolveConfiguredBaseUrl();
        if (configuredUrl != null) {
            renderWebFormSelfHosted(chatId, botToken, session, promptEvent,
                    title, description, codeContent, configuredUrl);
        } else {
            renderWebFormRelay(chatId, botToken, session, promptEvent,
                    title, description, codeContent);
        }
    }

    /** Self-hosted path: generates a token URL pointing to this app's {@code /input-form} endpoint. */
    private void renderWebFormSelfHosted(String chatId, String botToken, AiSession session,
                                          UiEventFrame promptEvent, String title, String description,
                                          String codeContent, String baseUrl) {
        String token = formTokenService.generateToken(
                session.uuid(), promptEvent.eventId(), session.username());
        String url = baseUrl + "/input-form/" + session.uuid() + "/" + promptEvent.eventId()
                + "?token=" + token;
        String urlEscaped = escapeMarkdownV2(url);
        String text = buildPromptTextMarkdownV2(title, description, codeContent,
                "\n\ud83d\udd17 Please complete the form: " + urlEscaped);
        telegramApiClient.sendTextMarkdownV2(botToken, chatId, text);
        log.debug("Self-hosted form URL sent [session={}, event={}]",
                session.uuid(), promptEvent.eventId());
    }

    /** Zero-knowledge relay path: encrypts the schema, uploads to relay, long-polls for response. */
    private void renderWebFormRelay(String chatId, String botToken, AiSession session,
                                     UiEventFrame promptEvent, String title, String description,
                                     String codeContent) {
        String relayBaseUrl   = DEFAULT_RELAY_BASE_URL;
        String relaySessionId = promptEvent.eventId();

        // ── 1. Encrypt form schema ─────────────────────────────────────────
        InteractionFormSchema schema = promptEvent.formSchema();
        RelayEncryptionService.EncryptionResult enc;
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            enc = relayEncryption.encrypt(schemaJson);
        } catch (Exception e) {
            log.error("Failed to encrypt form schema for relay [session={}, event={}]: {}",
                    session.uuid(), relaySessionId, e.getMessage(), e);
            String text = buildPromptTextMarkdownV2(title, description, codeContent,
                    "\n\u26a0\ufe0f Form preparation failed\\. Please try again or use the Vork web app\\.");
            telegramApiClient.sendTextMarkdownV2(botToken, chatId, text);
            return;
        }

        // ── 2. Upload ciphertext to relay ──────────────────────────────────
        int oobTimeoutMins = systemSettingsService.getDefaultOobTimeoutMinutes();
        try {
            relayHttpClient.upload(relayBaseUrl, relaySessionId,
                    enc.ciphertext(), enc.nonce(), enc.authTag(), oobTimeoutMins);
        } catch (Exception e) {
            log.error("Relay upload failed [session={}, event={}]: {}",
                    session.uuid(), relaySessionId, e.getMessage(), e);
            String text = buildPromptTextMarkdownV2(title, description, codeContent,
                    "\n\u26a0\ufe0f Could not reach the relay server\\. Please try again later\\.");
            telegramApiClient.sendTextMarkdownV2(botToken, chatId, text);
            return;
        }

        // ── 3. Send relay auth URL to Telegram ─────────────────────────────
        String authUrl     = relayBaseUrl + "/auth/" + relaySessionId + "#k=" + enc.keyBase64Url();
        String urlEscaped  = escapeMarkdownV2(authUrl);
        String text = buildPromptTextMarkdownV2(title, description, codeContent,
                "\n\ud83d\udd12 Please complete the secure form: " + urlEscaped);
        telegramApiClient.sendTextMarkdownV2(botToken, chatId, text);
        log.info("Relay form dispatched [session={}, event={}, relay={}]",
                session.uuid(), relaySessionId, relayBaseUrl);

        // ── 4. Long-poll on a virtual thread; resume session on response ───
        final javax.crypto.SecretKey sessionKey = enc.key();
        final String sessionUuid  = session.uuid();
        final String username     = session.username();
        Thread.ofVirtual().name("relay-poll-" + relaySessionId).start(() ->
                pollAndResume(relayBaseUrl, relaySessionId, sessionKey,
                              username, sessionUuid, chatId, botToken));
    }

    /**
     * Runs on a virtual thread: long-polls the relay until a response arrives,
     * decrypts it, resumes the suspended AI session, and sends the result to Telegram.
     * On re-suspension, calls {@link #render} recursively to handle the next prompt.
     */
    private void pollAndResume(String relayBaseUrl, String relaySessionId,
                                javax.crypto.SecretKey key,
                                String username, String sessionUuid,
                                String chatId, String botToken) {
        log.debug("ENTER pollAndResume [sessionId={}, sessionUuid={}]", relaySessionId, sessionUuid);
        int consecutiveErrors = 0;
        while (true) {
            // Abort if the session is no longer waiting for input
            AiSession current = sessionRepo.get(sessionUuid);
            if (current == null || current.status() != AiSessionStatus.AWAITING_INPUT) {
                log.debug("Session no longer awaiting input — stopping relay poll [session={}]",
                        sessionUuid);
                return;
            }

            RelaySubmission submission;
            try {
                submission = relayHttpClient.pollForResponse(relayBaseUrl, relaySessionId, 25_000);
                consecutiveErrors = 0;
            } catch (Exception e) {
                consecutiveErrors++;
                log.warn("Relay poll error [sessionId={}, attempt={}]: {}",
                        relaySessionId, consecutiveErrors, e.getMessage());
                if (consecutiveErrors >= 5) {
                    log.error("Relay poll giving up after {} consecutive errors [sessionId={}]",
                            consecutiveErrors, relaySessionId);
                    telegramApiClient.sendText(botToken, chatId,
                            "The relay connection was lost. Please try submitting the form again.");
                    return;
                }
                try { Thread.sleep(3_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            if (submission == null) {
                // 204 — no response yet; loop and retry
                continue;
            }

            // ── Decrypt the response ───────────────────────────────────────
            String responseJson;
            try {
                responseJson = relayEncryption.decrypt(
                        key, submission.encryptedResponse(), submission.nonce(), submission.authTag());
                log.debug("Relay response decrypted [sessionId={}]", relaySessionId);
            } catch (Exception e) {
                log.error("Failed to decrypt relay response [sessionId={}]: {}",
                        relaySessionId, e.getMessage(), e);
                telegramApiClient.sendText(botToken, chatId,
                        "Could not process the form response. Please try again.");
                return;
            }

            // ── Parse action + fields from relay response JSON ─────────────
            // Expected: {"action":"APPROVE","fields":{"password":"..."},"timestamp":"..."}
            String              action;
            Map<String, String> fields;
            try {
                Map<String, Object> responseMap = objectMapper.readValue(responseJson,
                        new TypeReference<Map<String, Object>>() {});
                action = String.valueOf(responseMap.getOrDefault("action", "ONCE"));
                @SuppressWarnings("unchecked")
                Map<String, Object> rawFields =
                        (Map<String, Object>) responseMap.getOrDefault("fields", Map.of());
                fields = new java.util.HashMap<>();
                rawFields.forEach((k, v) -> fields.put(k, v == null ? "" : String.valueOf(v)));
            } catch (Exception e) {
                log.error("Failed to parse relay response JSON [sessionId={}]: {}",
                        relaySessionId, e.getMessage(), e);
                telegramApiClient.sendText(botToken, chatId,
                        "Could not read the form response. Please try again.");
                return;
            }

            log.info("Relay response received — resuming session [session={}, action={}]",
                    sessionUuid, action);

            // ── Resume the suspended AI session ────────────────────────────
            try {
                String result = resumptionService.resumeAndRun(
                        username, sessionUuid, relaySessionId, action, fields);
                if (result != null && !result.isBlank()) {
                    telegramApiClient.sendText(botToken, chatId, result);
                }
            } catch (ToolSuspensionException ex) {
                // The resumed tool suspended again — render the new prompt
                log.debug("Session re-suspended after relay response [session={}]", sessionUuid);
                AiSession fresh = sessionRepo.get(sessionUuid);
                if (fresh == null) return;
                UiEventFrame nextPrompt = findLatestPromptEvent(fresh);
                if (nextPrompt == null) return;
                try {
                    render(chatId, botToken, fresh, nextPrompt);
                } catch (Exception re) {
                    log.error("Re-render after relay re-suspension failed [session={}]: {}",
                            sessionUuid, re.getMessage(), re);
                }
            } catch (Exception ex) {
                log.error("Session resumption failed after relay response [session={}]: {}",
                        sessionUuid, ex.getMessage(), ex);
                telegramApiClient.sendText(botToken, chatId,
                        "An error occurred while processing your response. Please try again.");
            }
            return;
        }
    }

    private UiEventFrame findLatestPromptEvent(AiSession session) {
        java.util.List<AiChatMessage> msgs = session.messages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            AiChatMessage m = msgs.get(i);
            if ("PROMPT_REQUIRED".equals(m.role())) {
                try {
                    return objectMapper.readValue(m.content(), UiEventFrame.class);
                } catch (Exception e) {
                    log.warn("Could not parse PROMPT_REQUIRED content [session={}]: {}",
                            session.uuid(), e.getMessage());
                }
            }
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Encodes a session UUID and action into a compact callback_data string.
     * Format: {@code r:{sessionUuid}:{X}} where X is one char (O/S/A/D).
     */
    public static String encodeCallback(String sessionUuid, String action) {
        char code = switch (action == null ? "" : action.toUpperCase()) {
            case "ONCE",    "ALLOW_ONCE"    -> 'O';
            case "SESSION", "ALLOW_SESSION" -> 'S';
            case "ALWAYS",  "ALLOW_ALWAYS"  -> 'A';
            default                          -> 'D'; // DENIED or unknown
        };
        return "r:" + sessionUuid + ":" + code;
    }

    /**
     * Decodes a callback_data string into {@code [sessionUuid, action]}.
     *
     * @return two-element array, or {@code null} if the format is unrecognized
     */
    public static String[] decodeCallback(String callbackData) {
        if (callbackData == null || !callbackData.startsWith("r:")) return null;
        String[] parts = callbackData.split(":", 3);
        if (parts.length != 3) return null;
        String sessionUuid = parts[1];
        String action = switch (parts[2]) {
            case "O" -> "ONCE";
            case "S" -> "SESSION";
            case "A" -> "ALWAYS";
            default  -> "DENIED";
        };
        return new String[]{ sessionUuid, action };
    }

    /**
     * Builds a MarkdownV2-safe prompt message. Title and description are escaped;
     * code content (from a tool's MARKDOWN display field) is wrapped in a code block
     * where only {@code `} and {@code \} are escaped per Telegram spec.
     * The {@code suffix} is appended verbatim — callers must pre-escape it if needed.
     */
    private static String buildPromptTextMarkdownV2(String title, String description,
                                                     String codeContent, String suffix) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(escapeMarkdownV2(title)).append("\n");
        }
        if (description != null && !description.isBlank()) {
            sb.append(escapeMarkdownV2(description));
        }
        if (codeContent != null && !codeContent.isBlank()) {
            sb.append("\n```\n").append(escapeCodeContent(codeContent)).append("\n```");
        }
        if (suffix != null && !suffix.isBlank()) {
            sb.append(suffix);
        }
        return sb.toString().trim();
    }

    /**
     * Extracts the plain-text content stored in the first MARKDOWN-typed field's
     * placeholder. {@code SecuredToolCallback} stores the web display value there
     * as a code-fenced string (e.g. {@code ```\nhost\n```}); this method strips
     * the fences so Telegram can re-wrap in its own code block.
     */
    private static String extractCodeContent(InteractionFormSchema schema) {
        if (schema == null || schema.fields() == null) return null;
        for (FormField field : schema.fields()) {
            if (field == null || !"MARKDOWN".equalsIgnoreCase(field.type())) continue;
            String value = field.placeholder();
            if (value == null || value.isBlank()) continue;
            return stripCodeFences(value);
        }
        return null;
    }

    /** Strips leading {@code ```[lang]} and trailing {@code ```} from a fenced block. */
    private static String stripCodeFences(String text) {
        if (!text.startsWith("```")) return text;
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) return text;
        String content = text.substring(firstNewline + 1);
        if (content.endsWith("```")) {
            content = content.substring(0, content.length() - 3);
        }
        if (content.endsWith("\n")) {
            content = content.substring(0, content.length() - 1);
        }
        return content;
    }

    /** Escapes all Telegram MarkdownV2 special characters in regular text. */
    static String escapeMarkdownV2(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("_",  "\\_")
                   .replace("*",  "\\*")
                   .replace("[",  "\\[")
                   .replace("]",  "\\]")
                   .replace("(",  "\\(")
                   .replace(")",  "\\)")
                   .replace("~",  "\\~")
                   .replace("`",  "\\`")
                   .replace(">",  "\\>")
                   .replace("#",  "\\#")
                   .replace("+",  "\\+")
                   .replace("-",  "\\-")
                   .replace("=",  "\\=")
                   .replace("|",  "\\|")
                   .replace("{",  "\\{")
                   .replace("}",  "\\}")
                   .replace(".",  "\\.")
                   .replace("!",  "\\!");
    }

    /**
     * Escapes characters that must be escaped inside Telegram MarkdownV2 code blocks:
     * only {@code `} and {@code \}.
     */
    private static String escapeCodeContent(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("`", "\\`");
    }

    /**
     * Returns the explicitly-configured base URL (from SystemSettings or property),
     * or {@code null} if nothing is set — meaning the relay default should be used.
     */
    private String resolveConfiguredBaseUrl() {
        sh.vork.setup.SystemSettings settings = systemSettingsService.getGlobal();
        if (settings != null && settings.appBaseUrl() != null && !settings.appBaseUrl().isBlank()) {
            String url = settings.appBaseUrl();
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        if (propertyBaseUrl != null && !propertyBaseUrl.isBlank()) {
            String url = propertyBaseUrl;
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        return null;
    }

}
