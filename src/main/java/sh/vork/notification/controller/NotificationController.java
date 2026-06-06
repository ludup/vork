package sh.vork.notification.controller;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.jadaptive.orm.DatabaseRepository;

import org.springframework.lang.Nullable;

import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProvider;
import sh.vork.notification.NotificationProviderConfig;
import sh.vork.notification.SettingDefinition;
import sh.vork.notification.telegram.TelegramPollingService;

/**
 * Page and REST controller for the Notifications management UI.
 *
 * <h3>Page</h3>
 * <ul>
 *   <li>{@code GET /settings/notifications} — handled by the generic
 *       {@code SettingsController}/{page} fallback, returns
 *       {@code settings/notifications} template.</li>
 * </ul>
 *
 * <h3>REST API</h3>
 * <ul>
 *   <li>{@code GET  /api/notifications/providers} — available provider types</li>
 *   <li>{@code GET  /api/notifications/configs}   — configured instances</li>
 *   <li>{@code POST /api/notifications/configs}   — create new config</li>
 *   <li>{@code PUT  /api/notifications/configs/{id}} — update existing config</li>
 *   <li>{@code DELETE /api/notifications/configs/{id}} — remove config</li>
 * </ul>
 *
 * <p>Password-type fields are <em>never</em> returned to the browser.  The
 * sentinel value {@code "••••"} is sent for already-saved secrets; clients
 * must preserve the existing value server-side if the user does not change it.
 */
@Controller
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);
    private static final String REDACTED = "••••";

    private final ApplicationContext applicationContext;
    private final DatabaseRepository<NotificationProviderConfig> configRepository;

    @Nullable
    private TelegramPollingService telegramPollingService;

    public NotificationController(ApplicationContext applicationContext,
                                  DatabaseRepository<NotificationProviderConfig> configRepository) {
        this.applicationContext = applicationContext;
        this.configRepository   = configRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTelegramPollingService(TelegramPollingService telegramPollingService) {
        this.telegramPollingService = telegramPollingService;
    }

    // ── Provider types ────────────────────────────────────────────────────────

    /**
     * Returns the list of available provider types (bean descriptors) so the
     * UI can offer a "choose provider" step.
     */
    @GetMapping("/api/notifications/providers")
    @ResponseBody
    public List<ProviderSummary> listProviders() {
        log.debug("ENTER listProviders");
        return discoverProviders().values().stream()
                .map(p -> new ProviderSummary(
                        p.getProviderKey(),
                        p.getDisplayName(),
                        p.getSettingDefinitions(),
                        p.getSupportedMediaTypes()))
                .collect(Collectors.toList());
    }

    // ── Configs ───────────────────────────────────────────────────────────────

    @GetMapping("/api/notifications/configs")
    @ResponseBody
    public List<ConfigView> listConfigs() {
        log.debug("ENTER listConfigs");
        try (var stream = configRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .map(c -> toView(c, discoverProviders().get(c.providerKey())))
                    .collect(Collectors.toList());
        }
    }

    @PostMapping("/api/notifications/configs")
    @ResponseBody
    public ResponseEntity<?> createConfig(@RequestBody ConfigRequest req) {
        log.debug("ENTER createConfig: [providerKey={}, displayName={}]",
                req.providerKey(), req.displayName());

        NotificationProvider provider = discoverProviders().get(req.providerKey());
        if (provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown provider: " + req.providerKey()));
        }

        Map<String, String> errors = provider.validate(req.settings() != null ? req.settings() : Map.of());
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(Map.of("fieldErrors", errors));

        NotificationProviderConfig config = new NotificationProviderConfig(
                UUID.randomUUID().toString(),
                req.providerKey(),
                req.displayName() != null ? req.displayName() : provider.getDisplayName(),
                sanitise(req.settings()));
        configRepository.save(config);
        log.info("Notification config created [id={}, provider={}]", config.uuid(), config.providerKey());
        if ("telegram".equals(config.providerKey()) && telegramPollingService != null) {
            telegramPollingService.startPolling(config);
        }
        return ResponseEntity.ok(toView(config, provider));
    }

    @PutMapping("/api/notifications/configs/{id}")
    @ResponseBody
    public ResponseEntity<?> updateConfig(@PathVariable String id, @RequestBody ConfigRequest req) {
        log.debug("ENTER updateConfig: [id={}]", id);

        NotificationProviderConfig existing = configRepository.get(id);
        if (existing == null) return ResponseEntity.notFound().build();

        NotificationProvider provider = discoverProviders().get(existing.providerKey());
        if (provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Provider no longer available."));
        }

        // Merge: keep existing secret values when the browser sends the redaction sentinel
        Map<String, String> merged = mergeSensitive(existing.settings(), req.settings(), provider);
        Map<String, String> errors = provider.validate(merged);
        if (!errors.isEmpty()) return ResponseEntity.badRequest().body(Map.of("fieldErrors", errors));

        NotificationProviderConfig updated = new NotificationProviderConfig(
                id,
                existing.providerKey(),
                req.displayName() != null ? req.displayName() : existing.displayName(),
                merged);
        configRepository.save(updated);
        log.info("Notification config updated [id={}]", id);
        return ResponseEntity.ok(toView(updated, provider));
    }

    @DeleteMapping("/api/notifications/configs/{id}")
    @ResponseBody
    public ResponseEntity<?> deleteConfig(@PathVariable String id) {
        log.debug("ENTER deleteConfig: [id={}]", id);
        NotificationProviderConfig existing = configRepository.get(id);
        if (existing == null) return ResponseEntity.notFound().build();
        configRepository.delete(id);
        log.info("Notification config deleted [id={}]", id);
        if ("telegram".equals(existing.providerKey()) && telegramPollingService != null) {
            telegramPollingService.stopPolling(id);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, NotificationProvider> discoverProviders() {
        return applicationContext.getBeansOfType(NotificationProvider.class)
                .values().stream()
                .collect(Collectors.toMap(NotificationProvider::getProviderKey, p -> p,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /** Replace sensitive fields with {@link #REDACTED} sentinel for browser responses. */
    private ConfigView toView(NotificationProviderConfig config, NotificationProvider provider) {
        Map<String, String> safeSettings = new HashMap<>(config.settings());
        if (provider != null) {
            provider.getSettingDefinitions().stream()
                    .filter(d -> "password".equals(d.type()) && safeSettings.containsKey(d.key()))
                    .forEach(d -> safeSettings.put(d.key(), REDACTED));
        }
        return new ConfigView(config.uuid(), config.providerKey(), config.displayName(), safeSettings);
    }

    /**
     * When the browser sends back the {@link #REDACTED} sentinel for a password field,
     * restore the previously-saved value instead of overwriting with the sentinel.
     */
    private static Map<String, String> mergeSensitive(Map<String, String> existing,
                                                       Map<String, String> incoming,
                                                       NotificationProvider provider) {
        Map<String, String> result = new HashMap<>(incoming != null ? incoming : Map.of());
        for (SettingDefinition def : provider.getSettingDefinitions()) {
            if ("password".equals(def.type())) {
                String sent = result.get(def.key());
                if (REDACTED.equals(sent) || sent == null) {
                    // User did not change this field – restore persisted value
                    String savedValue = existing.get(def.key());
                    if (savedValue != null) result.put(def.key(), savedValue);
                }
            }
        }
        return result;
    }

    /** Strip any null values so they don't pollute the settings map. */
    private static Map<String, String> sanitise(Map<String, String> raw) {
        if (raw == null) return Map.of();
        Map<String, String> clean = new HashMap<>();
        raw.forEach((k, v) -> { if (k != null && v != null) clean.put(k, v); });
        return clean;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record ProviderSummary(
            String                     providerKey,
            String                     displayName,
            List<SettingDefinition>    settingDefinitions,
            Set<NotificationMediaType> mediaTypes
    ) {}

    record ConfigView(
            String              uuid,
            String              providerKey,
            String              displayName,
            Map<String, String> settings
    ) {}

    record ConfigRequest(
            String              providerKey,
            String              displayName,
            Map<String, String> settings
    ) {}
}
