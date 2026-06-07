package sh.vork.ai.telegram;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jadaptive.orm.DatabaseRepository;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.notification.telegram.TelegramApiClient;
import sh.vork.scheduling.service.AiSchedulerService;
import sh.vork.scheduling.service.SystemBackgroundAuthentication;

/**
 * Generic web-form controller for suspended AI sessions.
 *
 * <p>Handles the web-form leg of any tool-suspension flow — both Telegram-originated
 * sessions (where the Telegram bot sent the URL) and background-job sessions (where the
 * operator received an email/log notification with the URL).
 *
 * <h3>URL pattern</h3>
 * <pre>{@code GET /input-form/{sessionUuid}/{eventId}?token=...}</pre>
 * <pre>{@code POST /input-form/{sessionUuid}/{eventId}?token=...}</pre>
 *
 * <p>The token is single-use and expires after 15 minutes.  Access is permitted without
 * a login session (token acts as one-time credential).
 *
 * <h3>Post-submission behaviour by session origin</h3>
 * <ul>
 *   <li><b>TELEGRAM</b> — full AI continuation loop runs; result is sent back via the bot.</li>
 *   <li><b>BACKGROUND</b> — fields are processed, the tool is executed, and
 *       {@link AiSchedulerService#resumeBackgroundSession(String)} is kicked off on
 *       the background executor.  The web page shows a "processing" confirmation.</li>
 *   <li><b>WEB / other</b> — same as TELEGRAM (falls through to AI continuation).</li>
 * </ul>
 */
@Controller
@RequestMapping("/input-form")
public class InputFormController {

    private static final Logger log = LoggerFactory.getLogger(InputFormController.class);

    private final InputFormTokenService         formTokenService;
    private final TelegramChatResumptionService resumptionService;
    private final DatabaseRepository<AiSession> sessionRepo;
    private final TelegramApiClient             telegramApiClient;
    private final AiSchedulerService            aiSchedulerService;
    private final Executor                      aiBackgroundExecutor;
    private final ObjectMapper                  objectMapper;

    public InputFormController(InputFormTokenService formTokenService,
                                TelegramChatResumptionService resumptionService,
                                DatabaseRepository<AiSession> sessionRepo,
                                TelegramApiClient telegramApiClient,
                                AiSchedulerService aiSchedulerService,
                                @Qualifier("aiBackgroundExecutor") Executor aiBackgroundExecutor,
                                ObjectMapper objectMapper) {
        this.formTokenService    = formTokenService;
        this.resumptionService   = resumptionService;
        this.sessionRepo         = sessionRepo;
        this.telegramApiClient   = telegramApiClient;
        this.aiSchedulerService  = aiSchedulerService;
        this.aiBackgroundExecutor = aiBackgroundExecutor;
        this.objectMapper        = objectMapper;
    }

    // ── GET — render form ─────────────────────────────────────────────────────

    @GetMapping("/{sessionUuid}/{eventId}")
    public String showForm(@PathVariable String sessionUuid,
                            @PathVariable String eventId,
                            @RequestParam String token,
                            Model model) {

        log.debug("ENTER showForm: session={}, event={}", sessionUuid, eventId);

        InputFormTokenService.TokenClaims claims = formTokenService.validateToken(token);
        if (claims == null) {
            log.warn("Invalid or expired token for input form [session={}, event={}]",
                    sessionUuid, eventId);
            model.addAttribute("errorMessage", "This link is invalid or has expired.");
            return "input-form-error";
        }

        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null || session.status() != AiSessionStatus.AWAITING_INPUT) {
            model.addAttribute("errorMessage", "This prompt is no longer active.");
            return "input-form-error";
        }

        UiEventFrame promptEvent = findPromptEvent(session, eventId);
        if (promptEvent == null) {
            model.addAttribute("errorMessage", "Could not locate the pending prompt.");
            return "input-form-error";
        }

        InteractionFormSchema schema = promptEvent.formSchema();
        List<FormField> visibleFields = schema == null || schema.fields() == null
                ? List.of()
                : schema.fields().stream()
                        .filter(f -> f != null && !isInvisibleType(f.type()))
                        .collect(Collectors.toList());

        model.addAttribute("sessionUuid",  sessionUuid);
        model.addAttribute("eventId",      eventId);
        model.addAttribute("token",        token);
        model.addAttribute("title",        schema != null ? schema.title() : "Action required");
        model.addAttribute("description",  promptEvent.textResponse());
        model.addAttribute("fields",       visibleFields);
        model.addAttribute("actions",      schema != null && schema.actions() != null
                ? schema.actions() : List.of());

        boolean isBackground = session.originMode() == SessionOriginMode.BACKGROUND;
        model.addAttribute("isBackground", isBackground);

        log.debug("EXIT showForm: rendering form with {} field(s), origin={}",
                visibleFields.size(), session.originMode());
        return "input-form";
    }

    // ── POST — submit form ────────────────────────────────────────────────────

    @PostMapping("/{sessionUuid}/{eventId}")
    public String submitForm(@PathVariable String sessionUuid,
                              @PathVariable String eventId,
                              @RequestParam String token,
                              @RequestParam Map<String, String> params,
                              Model model) {

        log.debug("ENTER submitForm: session={}, event={}", sessionUuid, eventId);

        InputFormTokenService.TokenClaims claims = formTokenService.validateToken(token);
        if (claims == null) {
            model.addAttribute("errorMessage", "This link is invalid or has expired.");
            return "input-form-error";
        }

        // Consume the token immediately to prevent replay
        formTokenService.consumeToken(token);

        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null || session.status() != AiSessionStatus.AWAITING_INPUT) {
            model.addAttribute("errorMessage", "This prompt is no longer active.");
            return "input-form-error";
        }

        // Extract the chosen action (submitted as a button's name/value)
        String action = params.getOrDefault("_action", "ONCE");

        // Build field map — strip reserved params
        Map<String, String> fields = params.entrySet().stream()
                .filter(e -> !e.getKey().startsWith("_") && !"token".equals(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        SessionOriginMode origin = session.originMode();

        try {
            if (origin == SessionOriginMode.BACKGROUND) {
                return handleBackgroundSubmit(claims, sessionUuid, eventId, action, fields, model);
            } else {
                return handleInteractiveSubmit(claims, sessionUuid, eventId, action, fields, session, model);
            }
        } catch (Exception ex) {
            log.warn("Error submitting input form [session={}]: {}", sessionUuid, ex.getMessage(), ex);
            model.addAttribute("errorMessage", "An error occurred while processing your response.");
            return "input-form-error";
        }
    }

    // ── Submission strategies ─────────────────────────────────────────────────

    /**
     * Handles form submission for background-origin sessions.
     * Processes fields + executes the tool, then resumes the background engine on its
     * dedicated executor thread pool.
     */
    private String handleBackgroundSubmit(InputFormTokenService.TokenClaims claims,
                                           String sessionUuid, String eventId,
                                           String action, Map<String, String> fields,
                                           Model model) {
        log.info("Background form submit [session={}, user={}]", sessionUuid, claims.username());
        try {
            // Process fields + execute tool; saves session as RUNNING so the engine can pick up
            resumptionService.processAndActivate(
                    claims.username(), sessionUuid, eventId, action, fields);

        } catch (ToolSuspensionException ex) {
            // Tool suspended immediately again — we still started the engine; it will handle it
            log.info("Tool re-suspended during background form submit [session={}]", sessionUuid);
        }

        // Kick off the background engine on its isolated thread pool (mirrors ChatAuthorizationController)
        String username = claims.username();
        aiBackgroundExecutor.execute(() -> {
            ToolExecutionContext.bindSessionUuid(sessionUuid);
            AiSession fresh = sessionRepo.get(sessionUuid);
            if (fresh != null) ToolExecutionContext.hydrate(fresh.environmentVariables());
            try {
                SecurityContextHolder.getContext()
                        .setAuthentication(new SystemBackgroundAuthentication(username));
                aiSchedulerService.resumeBackgroundSession(sessionUuid);
            } catch (Exception ex) {
                log.error("Background resume failed after form submit [session={}]: {}",
                        sessionUuid, ex.getMessage(), ex);
            } finally {
                SecurityContextHolder.clearContext();
                ToolExecutionContext.clear();
            }
        });

        model.addAttribute("message",
                "Your response was submitted. The background task is resuming — "
                + "check the Jobs panel for progress.");
        log.info("Background session re-activated via input form [session={}]", sessionUuid);
        return "input-form-done";
    }

    /**
     * Handles form submission for interactive-origin sessions (TELEGRAM, WEB, etc.).
     * Runs the full AI continuation loop and, for TELEGRAM sessions, sends the reply via bot.
     */
    private String handleInteractiveSubmit(InputFormTokenService.TokenClaims claims,
                                            String sessionUuid, String eventId,
                                            String action, Map<String, String> fields,
                                            AiSession session, Model model) {
        try {
            String result = resumptionService.resumeAndRun(
                    claims.username(), sessionUuid, eventId, action, fields);

            if (session.originMode() == SessionOriginMode.TELEGRAM) {
                String chatId   = session.environmentVariables().get("TELEGRAM_CHAT_ID");
                String botToken = session.environmentVariables().get("TELEGRAM_BOT_TOKEN");
                if (chatId != null && botToken != null && result != null && !result.isBlank()) {
                    telegramApiClient.sendText(botToken, chatId, result);
                }
            }

            model.addAttribute("message", "Your response was submitted successfully. "
                    + (session.originMode() == SessionOriginMode.TELEGRAM
                            ? "Check Telegram for the reply."
                            : "The AI session has continued."));
            log.info("Input form submitted [session={}, user={}, origin={}]",
                    sessionUuid, claims.username(), session.originMode());
            return "input-form-done";

        } catch (ToolSuspensionException ex) {
            // Another suspension — Telegram/web consumer will handle the next prompt
            model.addAttribute("message", "Your response was submitted. "
                    + "Another confirmation may be required — please check for a new prompt.");
            return "input-form-done";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UiEventFrame findPromptEvent(AiSession session, String eventId) {
        var messages = session.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            var m = messages.get(i);
            if ("PROMPT_REQUIRED".equals(m.role())) {
                try {
                    UiEventFrame frame = objectMapper.readValue(m.content(), UiEventFrame.class);
                    if (eventId == null || eventId.equals(frame.eventId())) return frame;
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    private static boolean isInvisibleType(String type) {
        if (type == null) return false;
        String t = type.toUpperCase();
        return "HIDDEN".equals(t) || "MARKDOWN".equals(t);
    }
}
