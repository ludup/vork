package sh.vork.scheduling.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.ai.telegram.InputFormTokenService;
import sh.vork.ai.telegram.TelegramChatResumptionService;
import sh.vork.notification.Notification;
import sh.vork.notification.NotificationProvider;
import sh.vork.notification.NotificationProviderConfig;
import sh.vork.notification.user.UserNotificationMedia;
import sh.vork.relay.RelayEncryptionService;
import sh.vork.relay.RelayHttpClient;
import sh.vork.relay.lib.model.RelaySubmission;
import sh.vork.setup.SystemSettings;
import sh.vork.setup.SystemSettingsService;
import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.SearchQuery;
import com.jadaptive.orm.SortOrder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Notifies operators of pending background-session authorizations via the zero-knowledge
 * vork-relay protocol.
 *
 * <p>The form schema is AES-256-GCM encrypted and uploaded to the relay. A secure URL
 * ({@code /auth/{eventId}#k=...}) is logged for the operator to open. The service then
 * long-polls the relay in the background and resumes the suspended AI session once a
 * response is received.
 *
 * <p>Relay base URL priority: SystemSettings → {@code vork.app.base-url} property →
 * {@code https://relay.vork.sh} (default).
 */
@Service
public class BackgroundNotificationService implements SystemNotificationService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundNotificationService.class);
    private static final String DEFAULT_RELAY_BASE_URL = "https://relay.vork.sh";

    private final SystemSettingsService          systemSettingsService;
    private final DatabaseRepository<AiSession>  sessionRepo;
    private final InputFormTokenService          formTokenService;
    private final RelayEncryptionService         relayEncryption;
    private final RelayHttpClient                relayHttpClient;
    private final TelegramChatResumptionService  resumptionService;
    private final AiSchedulerService             aiSchedulerService;
    private final Executor                       aiBackgroundExecutor;
    private final ObjectMapper                   objectMapper;
    private final DatabaseRepository<UserNotificationMedia>     mediaRepo;
    private final DatabaseRepository<NotificationProviderConfig> providerConfigRepo;
    private final ApplicationContext             applicationContext;

    @Value("${vork.app.base-url:}")
    private String propertyBaseUrl;

    public BackgroundNotificationService(SystemSettingsService systemSettingsService,
                                       DatabaseRepository<AiSession> sessionRepo,
                                       InputFormTokenService formTokenService,
                                       RelayEncryptionService relayEncryption,
                                       RelayHttpClient relayHttpClient,
                                       @Lazy TelegramChatResumptionService resumptionService,
                                       @Lazy AiSchedulerService aiSchedulerService,
                                       @Qualifier("aiBackgroundExecutor") Executor aiBackgroundExecutor,
                                       ObjectMapper objectMapper,
                                       DatabaseRepository<UserNotificationMedia> mediaRepo,
                                       DatabaseRepository<NotificationProviderConfig> providerConfigRepo,
                                       ApplicationContext applicationContext) {
        this.systemSettingsService = systemSettingsService;
        this.sessionRepo           = sessionRepo;
        this.formTokenService      = formTokenService;
        this.relayEncryption       = relayEncryption;
        this.relayHttpClient       = relayHttpClient;
        this.resumptionService     = resumptionService;
        this.aiSchedulerService    = aiSchedulerService;
        this.aiBackgroundExecutor  = aiBackgroundExecutor;
        this.objectMapper          = objectMapper;
        this.mediaRepo             = mediaRepo;
        this.providerConfigRepo    = providerConfigRepo;
        this.applicationContext    = applicationContext;
    }

    @Override
    public void notifyOfflineOperator(String toolName, String arguments,
                                       String sessionUuid, String eventId) {
        log.debug("ENTER notifyOfflineOperator: tool={}, session={}, event={}", toolName, sessionUuid, eventId);

        String username = resolveUsername(sessionUuid);

        // If appBaseUrl is explicitly configured, use the self-hosted /input-form URL.
        // Only use the zero-knowledge relay when no custom URL is set.
        String configuredUrl = resolveConfiguredBaseUrl();
        if (configuredUrl != null) {
            notifySelfHosted(toolName, arguments, sessionUuid, eventId, username, configuredUrl);
        } else {
            notifyViaRelay(toolName, arguments, sessionUuid, eventId, username);
        }
    }

    /** Self-hosted path: log a token URL pointing to this app's {@code /input-form} endpoint. */
    private void notifySelfHosted(String toolName, String arguments,
                                   String sessionUuid, String eventId,
                                   String username, String baseUrl) {
        String token = formTokenService.generateToken(sessionUuid, eventId, username);
        String url = baseUrl + "/input-form/" + sessionUuid + "/"
                + (eventId == null ? "latest" : eventId)
                + "?token=" + token;
        log.warn("[BACKGROUND AUTHORIZATION REQUIRED] tool='{}' session={} event={}",
                toolName, sessionUuid, eventId);
        log.warn("[BACKGROUND AUTHORIZATION REQUIRED] Open this URL to review and approve/deny: {}", url);
        log.warn("[BACKGROUND AUTHORIZATION REQUIRED] Restricted tool args snapshot: {}",
                arguments == null ? "{}" : arguments);
        log.debug("EXIT notifyOfflineOperator: self-hosted URL logged [event={}]", eventId);
    }

    /** Zero-knowledge relay path: encrypt, upload, log relay URL, long-poll for response. */
    private void notifyViaRelay(String toolName, String arguments,
                                 String sessionUuid, String eventId, String username) {
        String relayBaseUrl   = DEFAULT_RELAY_BASE_URL;
        String relaySessionId = eventId != null ? eventId : sessionUuid;

        // ── Resolve OOB timeout ──────────────────────────────────────────────
        int oobTimeoutMins = resolveOobTimeoutMins(sessionUuid);

        // ── Load the form schema from the session ────────────────────────────
        InteractionFormSchema schema = loadFormSchema(sessionUuid, relaySessionId);
        if (schema == null) {
            log.warn("[BACKGROUND AUTHORIZATION REQUIRED] tool='{}' session={} event={} " +
                    "— could not load form schema; falling back to log-only notification",
                    toolName, sessionUuid, eventId);
            log.warn("[BACKGROUND AUTHORIZATION REQUIRED] Restricted tool args snapshot: {}",
                    arguments == null ? "{}" : arguments);
            return;
        }

        // ── Encrypt the schema ──────────────────────────────────────────────
        RelayEncryptionService.EncryptionResult enc;
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            enc = relayEncryption.encrypt(schemaJson);
        } catch (Exception e) {
            log.error("[BACKGROUND AUTHORIZATION REQUIRED] Encryption failed [session={}, event={}]: {}",
                    sessionUuid, eventId, e.getMessage(), e);
            return;
        }

        // ── Upload to relay ─────────────────────────────────────────────────
        try {
            relayHttpClient.upload(relayBaseUrl, relaySessionId,
                    enc.ciphertext(), enc.nonce(), enc.authTag(), oobTimeoutMins);
        } catch (Exception e) {
            log.error("[BACKGROUND AUTHORIZATION REQUIRED] Relay upload failed [session={}, event={}]: {}",
                    sessionUuid, eventId, e.getMessage(), e);
            return;
        }

        // ── Log the secure relay URL ────────────────────────────────────────
        String authUrl = relayBaseUrl + "/auth/" + relaySessionId + "#k=" + enc.keyBase64Url();
        log.warn("[BACKGROUND AUTHORIZATION REQUIRED] tool='{}' session={} event={}",
                toolName, sessionUuid, eventId);
        log.warn("[BACKGROUND AUTHORIZATION REQUIRED] Open this URL to review and approve/deny: {}",
                authUrl);
        log.warn("[BACKGROUND AUTHORIZATION REQUIRED] Restricted tool args snapshot: {}",
                arguments == null ? "{}" : arguments);

        // ── Dispatch out-of-band notification to user's addresses ───────────
        dispatchOobNotifications(username, toolName, authUrl);

        // ── Long-poll on a virtual thread; resume session on response ────────
        final javax.crypto.SecretKey sessionKey = enc.key();
        final int capturedTimeout = oobTimeoutMins;
        Thread.ofVirtual().name("relay-bg-poll-" + relaySessionId).start(() ->
                pollAndResumeBackground(relayBaseUrl, relaySessionId, sessionKey,
                                        username, sessionUuid, capturedTimeout));

        log.debug("EXIT notifyOfflineOperator: relay dispatched [event={}]", relaySessionId);
    }

    // ── Private: OOB timeout resolution ──────────────────────────────────────

    /**
     * Resolves the effective OOB timeout for the given session.
     * For BACKGROUND sessions reads {@code vork.oob.timeout.minutes} from the session's
     * environment variables (set by AiJobRunner from the scheduled job config).
     * Falls through to the system default for Telegram/interactive sessions.
     */
    private int resolveOobTimeoutMins(String sessionUuid) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session != null) {
            String envVal = session.environmentVariables().get("vork.oob.timeout.minutes");
            if (envVal != null && !envVal.isBlank()) {
                try {
                    int v = Integer.parseInt(envVal);
                    if (v > 0) {
                        log.debug("OOB timeout from session env [session={}, minutes={}]", sessionUuid, v);
                        return v;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return systemSettingsService.getDefaultOobTimeoutMinutes();
    }

    // ── Private: OOB notification dispatch ──────────────────────────────

    /**
     * Sends the relay auth URL to the user's OOB-enabled notification addresses.
     * Falls back to ALL addresses if none are flagged as OOB.
     * Delivery is best-effort: failures are logged but do not interrupt the relay poll.
     */
    private void dispatchOobNotifications(String username, String toolName, String authUrl) {
        List<UserNotificationMedia> allAddresses;
        try (var stream = mediaRepo.search(0, Integer.MAX_VALUE, "createdAt", SortOrder.ASC,
                SearchQuery.eq("userId", username))) {
            allAddresses = stream.collect(Collectors.toList());
        }
        if (allAddresses.isEmpty()) {
            log.debug("No notification addresses for user '{}' — skipping OOB dispatch", username);
            return;
        }

        List<UserNotificationMedia> targets = allAddresses.stream()
                .filter(UserNotificationMedia::oobEnabled)
                .collect(Collectors.toList());

        if (targets.isEmpty()) {
            log.debug("No OOB-flagged addresses for '{}', falling back to all addresses", username);
            targets = allAddresses;
        }

        // Group by providerKey so we can send one call per provider config
        Map<String, List<UserNotificationMedia>> byProvider = targets.stream()
                .collect(Collectors.groupingBy(UserNotificationMedia::providerKey));

        Map<String, NotificationProvider> providerBeans =
                applicationContext.getBeansOfType(NotificationProvider.class)
                        .values().stream()
                        .collect(Collectors.toMap(NotificationProvider::getProviderKey,
                                p -> p, (a, b) -> a));

        for (Map.Entry<String, List<UserNotificationMedia>> entry : byProvider.entrySet()) {
            String providerConfigId = entry.getKey();
            List<String> recipients = entry.getValue().stream()
                    .map(UserNotificationMedia::address)
                    .collect(Collectors.toList());

            NotificationProviderConfig cfg = providerConfigRepo.get(providerConfigId);
            if (cfg == null) {
                log.warn("OOB dispatch: provider config '{}' not found, skipping", providerConfigId);
                continue;
            }
            NotificationProvider provider = providerBeans.get(cfg.providerKey());
            if (provider == null) {
                log.warn("OOB dispatch: no provider bean for key '{}', skipping", cfg.providerKey());
                continue;
            }

            Notification notification = Notification.of(
                    recipients,
                    "🔔 Background task requires authorization",
                    "Tool '" + toolName + "' is awaiting your approval.\n\n" +
                    "Open this secure link to review and respond:\n" + authUrl);

            try {
                provider.send(notification, cfg.settings());
                log.info("OOB notification sent via '{}' to {} recipient(s) [user={}]",
                        cfg.providerKey(), recipients.size(), username);
            } catch (Exception e) {
                log.warn("OOB notification delivery failed via '{}' [user={}, error={}]",
                        cfg.providerKey(), username, e.getMessage());
            }
        }
    }

    // ── Private: relay poll + background resumption ───────────────────────────

    private void pollAndResumeBackground(String relayBaseUrl, String relaySessionId,
                                          javax.crypto.SecretKey key,
                                          String username, String sessionUuid,
                                          int oobTimeoutMins) {
        log.debug("ENTER pollAndResumeBackground [relaySessionId={}, sessionUuid={}, oobTimeoutMins={}]",
                relaySessionId, sessionUuid, oobTimeoutMins);
        Instant deadline = Instant.now().plus(oobTimeoutMins, ChronoUnit.MINUTES);
        int consecutiveErrors = 0;
        while (true) {
            if (Instant.now().isAfter(deadline)) {
                log.warn("[BACKGROUND AUTHORIZATION] OOB relay timeout expired after {} min — giving up [session={}]",
                        oobTimeoutMins, sessionUuid);
                return;
            }
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
                log.warn("Relay poll error (background) [sessionId={}, attempt={}]: {}",
                        relaySessionId, consecutiveErrors, e.getMessage());
                if (consecutiveErrors >= 5) {
                    log.error("Relay poll giving up after {} consecutive errors [sessionId={}]",
                            consecutiveErrors, relaySessionId);
                    return;
                }
                try { Thread.sleep(3_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            if (submission == null) {
                continue; // 204 — no response yet; retry
            }

            // ── Decrypt ────────────────────────────────────────────────────
            String responseJson;
            try {
                responseJson = relayEncryption.decrypt(
                        key, submission.encryptedResponse(), submission.nonce(), submission.authTag());
                log.debug("Background relay response decrypted [sessionId={}]", relaySessionId);
            } catch (Exception e) {
                log.error("Failed to decrypt background relay response [sessionId={}]: {}",
                        relaySessionId, e.getMessage(), e);
                return;
            }

            // ── Parse ──────────────────────────────────────────────────────
            String              action;
            Map<String, String> fields;
            try {
                Map<String, Object> responseMap = objectMapper.readValue(responseJson,
                        new TypeReference<Map<String, Object>>() {});
                action = String.valueOf(responseMap.getOrDefault("action", "ONCE"));
                @SuppressWarnings("unchecked")
                Map<String, Object> rawFields =
                        (Map<String, Object>) responseMap.getOrDefault("fields", Map.of());
                fields = new HashMap<>();
                rawFields.forEach((k, v) -> fields.put(k, v == null ? "" : String.valueOf(v)));
            } catch (Exception e) {
                log.error("Failed to parse background relay response JSON [sessionId={}]: {}",
                        relaySessionId, e.getMessage(), e);
                return;
            }

            log.info("Background relay response received — resuming session [session={}, action={}]",
                    sessionUuid, action);

            // ── Resume ─────────────────────────────────────────────────────
            try {
                resumptionService.processAndActivate(username, sessionUuid, relaySessionId,
                        action, fields);
            } catch (ToolSuspensionException ex) {
                log.info("Tool re-suspended during background relay resume [session={}]", sessionUuid);
            } catch (Exception ex) {
                log.error("processAndActivate failed for background relay resume [session={}]: {}",
                        sessionUuid, ex.getMessage(), ex);
                return;
            }

            final String capturedSessionUuid = sessionUuid;
            aiBackgroundExecutor.execute(() -> {
                ToolExecutionContext.bindSessionUuid(capturedSessionUuid);
                AiSession fresh = sessionRepo.get(capturedSessionUuid);
                if (fresh != null) ToolExecutionContext.hydrate(fresh.environmentVariables());
                SecurityContext prevCtx = SecurityContextHolder.getContext();
                try {
                    SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                    ctx.setAuthentication(new SystemBackgroundAuthentication(username));
                    SecurityContextHolder.setContext(ctx);
                    aiSchedulerService.resumeBackgroundSession(capturedSessionUuid);
                } catch (Exception ex) {
                    log.error("Background resume failed after relay response [session={}]: {}",
                            capturedSessionUuid, ex.getMessage(), ex);
                } finally {
                    SecurityContextHolder.setContext(prevCtx);
                    ToolExecutionContext.clear();
                }
            });
            return;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Loads the {@link InteractionFormSchema} for the given event from the session's
     * PROMPT_REQUIRED message.
     */
    private InteractionFormSchema loadFormSchema(String sessionUuid, String eventId) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) return null;
        for (int i = session.messages().size() - 1; i >= 0; i--) {
            AiChatMessage msg = session.messages().get(i);
            if (!"PROMPT_REQUIRED".equals(msg.role())) continue;
            try {
                UiEventFrame frame = objectMapper.readValue(msg.content(), UiEventFrame.class);
                if (eventId == null || eventId.equals(frame.eventId())) {
                    return frame.formSchema();
                }
            } catch (Exception e) {
                log.warn("Could not parse PROMPT_REQUIRED content [session={}, event={}]: {}",
                        sessionUuid, eventId, e.getMessage());
            }
        }
        return null;
    }

    private String resolveUsername(String sessionUuid) {
        if (sessionUuid == null) return "system";
        AiSession session = sessionRepo.get(sessionUuid);
        return session != null && session.username() != null ? session.username() : "system";
    }

    private String resolveConfiguredBaseUrl() {
        try {
            SystemSettings settings = systemSettingsService.getGlobal();
            if (settings != null && settings.appBaseUrl() != null && !settings.appBaseUrl().isBlank()) {
                String url = settings.appBaseUrl();
                return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            }
        } catch (Exception e) {
            log.warn("Could not read appBaseUrl from settings: {}", e.getMessage());
        }
        if (propertyBaseUrl != null && !propertyBaseUrl.isBlank()) {
            String url = propertyBaseUrl;
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
        return null;
    }

}
