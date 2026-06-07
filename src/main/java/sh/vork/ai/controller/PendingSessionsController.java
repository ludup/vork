package sh.vork.ai.controller;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.SearchQuery;
import com.jadaptive.orm.SortOrder;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;

/**
 * Provides a session-agnostic view of all AI sessions that are currently
 * {@link AiSessionStatus#AWAITING_INPUT} for the authenticated user.
 *
 * <p>Covers both {@code TELEGRAM} and {@code BACKGROUND} origin sessions so the
 * user can supply the required input directly from the web UI without needing a
 * relay token or a Telegram interaction.
 *
 * <p>Submission is handled by the existing
 * {@code POST /api/chat/respond/{sessionUuid}} endpoint — the same one used by
 * the chat authorization card flow — which routes correctly based on origin mode.
 */
@Controller
public class PendingSessionsController {

    private static final Logger log = LoggerFactory.getLogger(PendingSessionsController.class);

    private final DatabaseRepository<AiSession> sessionRepo;
    private final ObjectMapper objectMapper;

    public PendingSessionsController(DatabaseRepository<AiSession> sessionRepo,
                                     ObjectMapper objectMapper) {
        this.sessionRepo   = sessionRepo;
        this.objectMapper  = objectMapper;
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/pending-sessions")
    public String pendingSessionsPage() {
        log.debug("ENTER pendingSessionsPage");
        return "pending-sessions";
    }

    // ── REST: list pending-input sessions ─────────────────────────────────────

    /**
     * Returns all {@link AiSessionStatus#AWAITING_INPUT} sessions for the current
     * user that originated from Telegram or a background job.
     */
    @GetMapping("/api/chat/sessions/pending-input")
    @ResponseBody
    public List<PendingSessionSummary> pendingInputSessions() {
        String username = resolveUsername();
        log.debug("ENTER pendingInputSessions: user={}", username);

        List<PendingSessionSummary> result = new ArrayList<>();

        try (var stream = sessionRepo.search(
                0, 100, "createdAt", SortOrder.DESC,
                SearchQuery.eq("username", username),
                SearchQuery.eq("status", AiSessionStatus.AWAITING_INPUT.name()),
                SearchQuery.in("originMode", "TELEGRAM", "BACKGROUND"))) {

            stream.forEach(session -> {
                AiChatMessage promptMessage = findLastPromptMessage(session.messages());
                if (promptMessage == null) return;

                try {
                    UiEventFrame frame = objectMapper.readValue(
                            promptMessage.content(), UiEventFrame.class);
                    if (frame == null) return;

                    result.add(new PendingSessionSummary(
                            session.uuid(),
                            session.name(),
                            session.originMode().name(),
                            session.createdAt(),
                            frame.eventId(),
                            promptMessage.toolName(),
                            frame.textResponse(),
                            frame.formSchema(),
                            promptMessage.toolCallId()
                    ));
                } catch (Exception ex) {
                    log.debug("Skipping session — failed to parse prompt event [session={}, error={}]",
                            session.uuid(), ex.getMessage());
                }
            });
        }

        log.debug("EXIT pendingInputSessions: found {} pending session(s) for user={}", result.size(), username);
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AiChatMessage findLastPromptMessage(List<AiChatMessage> messages) {
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiChatMessage msg = messages.get(i);
            if ("PROMPT_REQUIRED".equals(msg.role())) {
                return msg;
            }
        }
        return null;
    }

    private static String resolveUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "anonymous";
        }
        return auth.getName();
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    /**
     * Serializable summary of a single session waiting for user input.
     *
     * @param sessionUuid  the AI session UUID (used as the resume endpoint path variable)
     * @param sessionName  human-friendly session name
     * @param originMode   {@code TELEGRAM} or {@code BACKGROUND}
     * @param createdAt    epoch-milliseconds when the session was created
     * @param eventId      the pending prompt event ID (needed in the resume request)
     * @param toolName     the name of the tool waiting for authorisation
     * @param reasoning    plain-text explanation provided by the AI (may be null)
     * @param formSchema   the full {@link InteractionFormSchema} for rendering the form
     * @param toolCallId   the tool call ID associated with this prompt
     */
    public record PendingSessionSummary(
            String sessionUuid,
            String sessionName,
            String originMode,
            long createdAt,
            String eventId,
            String toolName,
            String reasoning,
            InteractionFormSchema formSchema,
            String toolCallId
    ) {}
}
