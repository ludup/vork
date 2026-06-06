package sh.vork.notification.user;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.SearchQuery;
import com.jadaptive.orm.SortOrder;

import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProvider;
import sh.vork.notification.NotificationProviderConfig;
import sh.vork.notification.telegram.TelegramRegistrationService;

/**
 * Page and REST controller for the user-facing notification media management.
 *
 * <h3>Page endpoints</h3>
 * <ul>
 *   <li>{@code GET /notifications}       — media management table</li>
 *   <li>{@code GET /notifications/setup} — first-time setup wizard</li>
 * </ul>
 *
 * <h3>REST API</h3>
 * <ul>
 *   <li>{@code GET    /api/user/notification-media}          — list current user's media</li>
 *   <li>{@code POST   /api/user/notification-media}          — add a media address</li>
 *   <li>{@code DELETE /api/user/notification-media/{id}}     — remove a media address</li>
 *   <li>{@code PUT    /api/user/notification-media/{id}/default} — set as default</li>
 *   <li>{@code GET    /api/user/notification-media/providers} — configured providers only</li>
 * </ul>
 */
@Controller
public class UserNotificationController {

    private static final Logger log = LoggerFactory.getLogger(UserNotificationController.class);

    private static final Pattern EMAIL_RE =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern TELEGRAM_USERNAME_RE =
            Pattern.compile("^@[A-Za-z][A-Za-z0-9_]{4,31}$");
    private static final Pattern TELEGRAM_CHATID_RE =
            Pattern.compile("^-?\\d+$");

    private final ApplicationContext                           appContext;
    private final DatabaseRepository<UserNotificationMedia>   mediaRepo;
    private final DatabaseRepository<NotificationProviderConfig> configRepo;
    private final TelegramRegistrationService telegramRegistrationService;

    public UserNotificationController(
            ApplicationContext appContext,
            DatabaseRepository<UserNotificationMedia> mediaRepo,
            DatabaseRepository<NotificationProviderConfig> configRepo,
            TelegramRegistrationService telegramRegistrationService) {
        this.appContext  = appContext;
        this.mediaRepo   = mediaRepo;
        this.configRepo  = configRepo;
        this.telegramRegistrationService = telegramRegistrationService;
    }

    // ── Page endpoints ────────────────────────────────────────────────────────

    @GetMapping("/notifications")
    public String notificationsPage(Model model) {
        model.addAttribute("configuredProviders", buildProviderViews());
        return "notifications";
    }

    @GetMapping("/notifications/setup")
    public String notificationsSetup(Model model) {
        model.addAttribute("configuredProviders", buildProviderViews());
        return "setup/notifications";
    }

    // ── REST: providers available to this user ────────────────────────────────

    /** Returns notification providers that have at least one saved configuration. */
    @GetMapping("/api/user/notification-media/providers")
    @ResponseBody
    public List<ProviderView> listAvailableProviders() {
        return buildProviderViews();
    }

    // ── REST: Telegram QR registration ───────────────────────────────────────

    /**
     * Starts a QR-code-based Telegram registration and returns the deep-link URL
     * that should be rendered as a QR code in the browser.
     */
    @PostMapping("/api/user/notification-media/telegram/register")
    @ResponseBody
    public ResponseEntity<?> startTelegramRegistration(
            @RequestBody TelegramRegisterRequest req) {
        String userId = currentUserId();
        log.debug("ENTER startTelegramRegistration: [userId={}, configId={}]",
                userId, req.providerConfigId());
        try {
            TelegramRegistrationService.RegistrationInfo info =
                    telegramRegistrationService.startRegistration(
                            userId, req.providerConfigId(), req.isDefault());
            return ResponseEntity.ok(Map.of(
                    "registrationId", info.registrationId(),
                    "url",            info.url()));
        } catch (Exception e) {
            log.warn("Failed to start Telegram registration [userId={}, error={}]",
                    userId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Polls for completion of a pending Telegram registration. */
    @GetMapping("/api/user/notification-media/telegram/register/{registrationId}")
    @ResponseBody
    public ResponseEntity<?> pollTelegramRegistration(
            @PathVariable String registrationId) {
        log.debug("ENTER pollTelegramRegistration: [regId={}]", registrationId);
        TelegramRegistrationService.RegistrationStatus status =
                telegramRegistrationService.checkStatus(registrationId);
        return ResponseEntity.ok(Map.of(
                "status",  status.status(),
                "mediaId", status.mediaId() != null ? status.mediaId() : ""));
    }

    /** Cancels a pending Telegram registration (user dismissed the modal). */
    @DeleteMapping("/api/user/notification-media/telegram/register/{registrationId}")
    @ResponseBody
    public ResponseEntity<?> cancelTelegramRegistration(
            @PathVariable String registrationId) {
        log.debug("ENTER cancelTelegramRegistration: [regId={}]", registrationId);
        telegramRegistrationService.cancel(registrationId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── REST: user media ──────────────────────────────────────────────────────

    @GetMapping("/api/user/notification-media")
    @ResponseBody
    public List<MediaView> listMedia() {
        String userId = currentUserId();
        log.debug("ENTER listMedia: [userId={}]", userId);
        try (var stream = mediaRepo.search(0, Integer.MAX_VALUE, "createdAt", SortOrder.ASC,
                SearchQuery.eq("userId", userId))) {
            return stream.map(this::toView).collect(Collectors.toList());
        }
    }

    @PostMapping("/api/user/notification-media")
    @ResponseBody
    public ResponseEntity<?> addMedia(@RequestBody MediaRequest req) {
        String userId = currentUserId();
        log.debug("ENTER addMedia: [userId={}, providerKey={}, address={}]",
                userId, req.providerKey(), req.address());

        // Resolve provider
        ProviderView pv = buildProviderViews().stream()
                .filter(p -> p.providerKey().equals(req.providerKey()))
                .findFirst().orElse(null);
        if (pv == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Provider not found or not configured: " + req.providerKey()));
        }

        // Validate address
        String validationError = validateAddress(pv.mediaType(), req.address());
        if (validationError != null) {
            return ResponseEntity.badRequest().body(Map.of("addressError", validationError));
        }

        // Determine whether this should be the default (first entry for user)
        boolean makeDefault = req.isDefault() || userMediaCount(userId) == 0;

        // If making default, clear existing defaults
        if (makeDefault) clearDefaults(userId);

        UserNotificationMedia media = new UserNotificationMedia(
                UUID.randomUUID().toString(),
                userId,
                pv.providerKey(),
                pv.mediaType(),
                req.address().trim(),
                req.label() != null ? req.label().trim() : "",
                makeDefault,
                System.currentTimeMillis());
        mediaRepo.save(media);
        log.info("User notification media added [userId={}, type={}, provider={}]",
                userId, pv.mediaType(), pv.providerKey());
        return ResponseEntity.ok(toView(media));
    }

    @DeleteMapping("/api/user/notification-media/{id}")
    @ResponseBody
    public ResponseEntity<?> deleteMedia(@PathVariable String id) {
        String userId = currentUserId();
        log.debug("ENTER deleteMedia: [id={}, userId={}]", id, userId);
        UserNotificationMedia media = mediaRepo.get(id);
        if (media == null || !media.userId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }
        mediaRepo.delete(id);
        log.info("User notification media deleted [id={}, userId={}]", id, userId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PutMapping("/api/user/notification-media/{id}/default")
    @ResponseBody
    public ResponseEntity<?> setDefault(@PathVariable String id) {
        String userId = currentUserId();
        log.debug("ENTER setDefault: [id={}, userId={}]", id, userId);
        UserNotificationMedia media = mediaRepo.get(id);
        if (media == null || !media.userId().equals(userId)) {
            return ResponseEntity.notFound().build();
        }
        // Clear all existing defaults, then set this one
        clearDefaults(userId);
        mediaRepo.save(new UserNotificationMedia(
                media.uuid(), media.userId(), media.providerKey(),
                media.mediaType(), media.address(), media.label(),
                true, media.createdAt()));
        log.info("Default notification media set [id={}, userId={}]", id, userId);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "anonymous";
    }

    private long userMediaCount(String userId) {
        return mediaRepo.searchCount(SearchQuery.eq("userId", userId));
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

    /**
     * Builds views of providers that have at least one persisted configuration.
     */
    private List<ProviderView> buildProviderViews() {
        // Collect all configured provider config keys
        Map<String, NotificationProvider> providers = appContext
                .getBeansOfType(NotificationProvider.class)
                .values().stream()
                .collect(Collectors.toMap(NotificationProvider::getProviderKey, p -> p,
                        (a, b) -> a, LinkedHashMap::new));

        try (var configStream = configRepo.list(0, Integer.MAX_VALUE)) {
            return configStream
                    .filter(cfg -> providers.containsKey(cfg.providerKey()))
                    .map(cfg -> {
                        NotificationProvider p  = providers.get(cfg.providerKey());
                        NotificationMediaType mt = p.getSupportedMediaTypes().iterator().next();
                        return new ProviderView(
                                cfg.uuid(),
                                cfg.providerKey(),
                                cfg.displayName(),
                                p.getDisplayName(),
                                mt,
                                mediaTypePlaceholder(mt),
                                mediaTypeInputHint(mt));
                    })
                    .collect(Collectors.toList());
        }
    }

    /**
     * Validates an address according to the media type.
     *
     * @return an error message if invalid, {@code null} if valid
     */
    private String validateAddress(NotificationMediaType type, String address) {
        if (address == null || address.isBlank()) return "Address is required.";
        String trimmed = address.trim();
        return switch (type) {
            case EMAIL_ADDRESS -> EMAIL_RE.matcher(trimmed).matches()
                    ? null : "Please enter a valid email address.";
            case PHONE_NUMBER  -> validatePhone(trimmed);
            case TELEGRAM      -> (TELEGRAM_USERNAME_RE.matcher(trimmed).matches()
                                || TELEGRAM_CHATID_RE.matcher(trimmed).matches())
                    ? null : "Enter a Telegram username (@handle) or numeric chat ID.";
        };
    }

    /**
     * Validates a phone number using libphonenumber when available,
     * falling back to a strict E.164 pattern otherwise.
     */
    private String validatePhone(String number) {
        try {
            com.google.i18n.phonenumbers.PhoneNumberUtil util =
                    com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance();
            String defaultRegion = Locale.getDefault().getCountry();
            if (defaultRegion == null || defaultRegion.isBlank()) defaultRegion = "US";
            com.google.i18n.phonenumbers.Phonenumber.PhoneNumber parsed =
                    util.parse(number, defaultRegion);
            if (!util.isValidNumber(parsed)) {
                return "Please enter a valid phone number (e.g. +14155552671).";
            }
            return null;
        } catch (Exception e) {
            return "Please enter a valid phone number (e.g. +14155552671).";
        }
    }

    private static String mediaTypePlaceholder(NotificationMediaType type) {
        return switch (type) {
            case EMAIL_ADDRESS -> "user@example.com";
            case PHONE_NUMBER  -> "+14155552671";
            case TELEGRAM      -> "@yourusername or 123456789";
        };
    }

    private static String mediaTypeInputHint(NotificationMediaType type) {
        return switch (type) {
            case EMAIL_ADDRESS -> "Email address";
            case PHONE_NUMBER  -> "Phone number in international format (e.g. +14155552671)";
            case TELEGRAM      -> "Telegram @username or numeric chat ID";
        };
    }

    private MediaView toView(UserNotificationMedia m) {
        return new MediaView(m.uuid(), m.providerKey(), m.mediaType().name(),
                m.address(), m.label(), m.isDefault());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /** Provider that has at least one saved configuration. */
    record ProviderView(
            String               configId,
            String               providerKey,
            String               configDisplayName,
            String               providerDisplayName,
            NotificationMediaType mediaType,
            String               addressPlaceholder,
            String               addressHint
    ) {}

    record MediaView(
            String  uuid,
            String  providerKey,
            String  mediaType,
            String  address,
            String  label,
            boolean isDefault
    ) {}

    record MediaRequest(
            String  providerKey,
            String  address,
            String  label,
            boolean isDefault
    ) {}

    record TelegramRegisterRequest(String providerConfigId, boolean isDefault) {}
}
