package sh.vork.notification.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.SearchQuery;
import com.jadaptive.orm.SortOrder;

import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProviderConfig;
import sh.vork.notification.user.UserNotificationMedia;

/**
 * Manages the QR-code-based Telegram registration flow.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>UI calls {@link #startRegistration} → returns a {@link RegistrationInfo}
 *       with a unique code and a {@code https://t.me/BOT?start=CODE} URL.</li>
 *   <li>User scans the QR code; Telegram sends {@code /start CODE} to the bot.</li>
 *   <li>{@link TelegramRegistrationConsumer} calls {@link #complete} with the code
 *       and the user's chat ID and name.</li>
 *   <li>UI polling via {@link #checkStatus} detects completion and shows the new
 *       address in the table.</li>
 * </ol>
 *
 * <p>Pending registrations expire after 10 minutes.  State is held entirely
 * in memory; a server restart cancels any open registrations.
 */
@Service
public class TelegramRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramRegistrationService.class);
    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final Duration EXPIRY  = Duration.ofMinutes(10);

    /** registrationId → pending state */
    private final ConcurrentHashMap<String, PendingRegistration> byId   = new ConcurrentHashMap<>();
    /** one-time code → pending state */
    private final ConcurrentHashMap<String, PendingRegistration> byCode = new ConcurrentHashMap<>();

    private final DatabaseRepository<NotificationProviderConfig> configRepo;
    private final DatabaseRepository<UserNotificationMedia>      mediaRepo;
    private final ObjectMapper  objectMapper;
    private final HttpClient    httpClient;

    public TelegramRegistrationService(
            DatabaseRepository<NotificationProviderConfig> configRepo,
            DatabaseRepository<UserNotificationMedia>      mediaRepo,
            ObjectMapper objectMapper) {
        this.configRepo   = configRepo;
        this.mediaRepo    = mediaRepo;
        this.objectMapper = objectMapper;
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Initiates a new registration.
     *
     * @param userId          the authenticated user starting the registration
     * @param providerConfigId UUID of a saved Telegram {@link NotificationProviderConfig}
     * @param isDefault       whether the resulting address should be the user's default
     * @return metadata the UI needs to display the QR code
     */
    public RegistrationInfo startRegistration(String userId,
                                               String providerConfigId,
                                               boolean isDefault) throws Exception {
        log.debug("ENTER startRegistration: [userId={}, configId={}]", userId, providerConfigId);

        NotificationProviderConfig config = configRepo.get(providerConfigId);
        if (config == null || !"telegram".equals(config.providerKey())) {
            throw new IllegalArgumentException(
                    "Telegram provider config not found: " + providerConfigId);
        }
        String botToken   = config.settings().getOrDefault("botToken", "");
        String botUsername = fetchBotUsername(botToken);

        // 16-char uppercase alphanumeric code
        String code           = UUID.randomUUID().toString().replace("-", "")
                                    .substring(0, 16).toUpperCase();
        String registrationId = UUID.randomUUID().toString();
        String url            = "https://t.me/" + botUsername + "?start=" + code;

        PendingRegistration pending = new PendingRegistration(
                registrationId, userId, providerConfigId, code, isDefault, Instant.now());
        byId.put(registrationId, pending);
        byCode.put(code, pending);

        log.info("Telegram registration started [userId={}, regId={}, bot={}]",
                userId, registrationId, botUsername);
        return new RegistrationInfo(registrationId, url);
    }

    /**
     * Called by {@link TelegramRegistrationConsumer} when a {@code /start CODE}
     * message is received.
     *
     * @return {@code true} if the code was valid and registration succeeded
     */
    public boolean complete(String code, String chatId, String firstName) {
        log.debug("ENTER complete: [code={}, chatId={}]", code, chatId);

        PendingRegistration pending = byCode.get(code);
        if (pending == null) {
            log.debug("No pending registration for code");
            return false;
        }
        if (Instant.now().isAfter(pending.createdAt.plus(EXPIRY))) {
            byCode.remove(code);
            byId.remove(pending.registrationId);
            log.debug("Registration code expired");
            return false;
        }
        if (pending.complete) return true; // idempotent

        boolean makeDefault = pending.isDefault
                || mediaRepo.searchCount(SearchQuery.eq("userId", pending.userId)) == 0;
        if (makeDefault) clearDefaults(pending.userId);

        String label = (firstName != null && !firstName.isBlank()) ? firstName : "Telegram";
        UserNotificationMedia media = new UserNotificationMedia(
                UUID.randomUUID().toString(),
                pending.userId,
                pending.providerConfigId,
                NotificationMediaType.TELEGRAM,
                chatId,
                label,
                makeDefault,
                System.currentTimeMillis());
        mediaRepo.save(media);

        pending.markComplete(media.uuid());
        byCode.remove(code);

        log.info("Telegram registration completed [userId={}, chatId={}, label={}]",
                pending.userId, chatId, label);
        return true;
    }

    /**
     * Returns the current status of a pending registration.
     *
     * <p>Once {@code "complete"} or {@code "expired"} is returned the entry is
     * removed from memory.
     */
    public RegistrationStatus checkStatus(String registrationId) {
        PendingRegistration pending = byId.get(registrationId);
        if (pending == null) return new RegistrationStatus("expired", null);

        if (Instant.now().isAfter(pending.createdAt.plus(EXPIRY))) {
            byId.remove(registrationId);
            byCode.remove(pending.code);
            return new RegistrationStatus("expired", null);
        }
        if (pending.complete) {
            byId.remove(registrationId);
            return new RegistrationStatus("complete", pending.mediaId);
        }
        return new RegistrationStatus("pending", null);
    }

    /**
     * Cancels a pending registration (called when the user closes the modal
     * before completing).  No-op if the registration ID is unknown.
     */
    public void cancel(String registrationId) {
        PendingRegistration pending = byId.remove(registrationId);
        if (pending != null) {
            byCode.remove(pending.code);
            log.debug("Telegram registration cancelled [regId={}]", registrationId);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String fetchBotUsername(String botToken) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + botToken + "/getMe"))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(resp.body());
        if (!root.path("ok").asBoolean()) {
            throw new RuntimeException("Telegram getMe failed: " + resp.body());
        }
        String username = root.path("result").path("username").asText("");
        if (username.isBlank()) {
            throw new RuntimeException("Bot has no username — assign one in BotFather first.");
        }
        return username;
    }

    private void clearDefaults(String userId) {
        try (var stream = mediaRepo.search(0, Integer.MAX_VALUE, "createdAt", SortOrder.ASC,
                SearchQuery.eq("userId", userId),
                SearchQuery.eq("isDefault", true))) {
            stream.forEach(m -> mediaRepo.save(new UserNotificationMedia(
                    m.uuid(), m.userId(), m.providerKey(),
                    m.mediaType(), m.address(), m.label(),
                    false, m.createdAt())));
        }
    }

    // ── DTOs / value types ────────────────────────────────────────────────────

    public record RegistrationInfo(String registrationId, String url) {}

    public record RegistrationStatus(String status, String mediaId) {}

    /** Mutable in-memory state for one open registration. */
    static final class PendingRegistration {
        final String  registrationId;
        final String  userId;
        final String  providerConfigId;
        final String  code;
        final boolean isDefault;
        final Instant createdAt;

        volatile boolean complete = false;
        volatile String  mediaId  = null;

        PendingRegistration(String registrationId, String userId,
                             String providerConfigId, String code,
                             boolean isDefault, Instant createdAt) {
            this.registrationId   = registrationId;
            this.userId           = userId;
            this.providerConfigId = providerConfigId;
            this.code             = code;
            this.isDefault        = isDefault;
            this.createdAt        = createdAt;
        }

        synchronized void markComplete(String mediaId) {
            this.mediaId  = mediaId;
            this.complete = true;
        }
    }
}
