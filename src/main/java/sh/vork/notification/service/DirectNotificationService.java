package sh.vork.notification.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import com.jadaptive.orm.DatabaseRepository;

import sh.vork.notification.Notification;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProvider;
import sh.vork.notification.NotificationProviderConfig;

/**
 * Provides direct (unregistered-address) notification delivery for the AI tool layer.
 *
 * <p>Unlike user-scoped delivery (which routes through {@link sh.vork.notification.user.UserNotificationMedia}),
 * this service accepts an arbitrary address and a specific {@link NotificationProviderConfig} UUID
 * chosen by the caller.  Providers that do not support direct addressing (e.g. Telegram) are
 * excluded from discovery and cannot be used.
 */
@Service
public class DirectNotificationService {

    private static final Logger log = LoggerFactory.getLogger(DirectNotificationService.class);

    /** Inner record returned by {@link #listAvailable()} — safe to serialise to JSON. */
    public record ProviderSummary(
            String configId,
            String displayName,
            String providerKey,
            Set<NotificationMediaType> mediaTypes
    ) {}

    private final DatabaseRepository<NotificationProviderConfig> providerConfigRepo;
    private final ApplicationContext applicationContext;

    public DirectNotificationService(
            DatabaseRepository<NotificationProviderConfig> providerConfigRepo,
            ApplicationContext applicationContext) {
        this.providerConfigRepo = providerConfigRepo;
        this.applicationContext = applicationContext;
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    /**
     * Returns all configured notification providers that support direct
     * (unregistered) addressing.
     *
     * <p>A provider appears in this list only when:
     * <ol>
     *   <li>It implements {@link NotificationProvider#supportsDirectAddress()} returning
     *       {@code true}; and</li>
     *   <li>At least one {@link NotificationProviderConfig} with a matching
     *       {@code providerKey} exists in MongoDB.</li>
     * </ol>
     */
    public List<ProviderSummary> listAvailable() {
        log.debug("ENTER listAvailable");

        Map<String, NotificationProvider> providerBeans =
                applicationContext.getBeansOfType(NotificationProvider.class)
                        .values().stream()
                        .filter(NotificationProvider::supportsDirectAddress)
                        .collect(Collectors.toMap(NotificationProvider::getProviderKey, p -> p, (a, b) -> a));

        if (providerBeans.isEmpty()) {
            log.debug("EXIT listAvailable: no direct-address providers registered");
            return List.of();
        }

        List<ProviderSummary> result = new ArrayList<>();
        try (var stream = providerConfigRepo.list(0, Integer.MAX_VALUE)) {
            stream.forEach(cfg -> {
                NotificationProvider provider = providerBeans.get(cfg.providerKey());
                if (provider != null) {
                    result.add(new ProviderSummary(
                            cfg.uuid(),
                            cfg.displayName(),
                            cfg.providerKey(),
                            provider.getSupportedMediaTypes()
                    ));
                }
            });
        }

        log.debug("EXIT listAvailable: found {} provider config(s)", result.size());
        return result;
    }

    // ── Delivery ──────────────────────────────────────────────────────────────

    /**
     * Sends a notification to {@code address} using the provider config identified
     * by {@code providerConfigId}.
     *
     * @param providerConfigId UUID of the {@link NotificationProviderConfig} to use
     * @param title            subject / headline
     * @param body             plain-text body
     * @param address          delivery address (email or E.164 phone number)
     * @return {@code "ok"} on success, or a human-readable error string on failure
     */
    public String send(String providerConfigId, String title, String body, String address) {
        log.debug("ENTER send: providerConfigId={}, address={}", providerConfigId, address);

        NotificationProviderConfig cfg = providerConfigRepo.get(providerConfigId);
        if (cfg == null) {
            log.warn("send: provider config not found [configId={}]", providerConfigId);
            return "error: provider config '" + providerConfigId + "' not found";
        }

        Map<String, NotificationProvider> providerBeans =
                applicationContext.getBeansOfType(NotificationProvider.class)
                        .values().stream()
                        .collect(Collectors.toMap(NotificationProvider::getProviderKey, p -> p, (a, b) -> a));

        NotificationProvider provider = providerBeans.get(cfg.providerKey());
        if (provider == null) {
            log.warn("send: no provider bean for key '{}' [configId={}]", cfg.providerKey(), providerConfigId);
            return "error: no provider registered for key '" + cfg.providerKey() + "'";
        }

        if (!provider.supportsDirectAddress()) {
            log.warn("send: provider '{}' does not support direct addressing", cfg.providerKey());
            return "error: provider '" + cfg.displayName() + "' requires prior opt-in and cannot send to unregistered addresses";
        }

        try {
            Notification notification = Notification.of(List.of(address), title, body);
            provider.send(notification, cfg.settings());
            log.info("Direct notification sent via '{}' to '{}' [configId={}]",
                    cfg.providerKey(), address, providerConfigId);
            return "ok";
        } catch (Exception e) {
            log.warn("Direct notification delivery failed via '{}' [address={}, error={}]",
                    cfg.providerKey(), address, e.getMessage());
            return "error: delivery failed — " + e.getMessage();
        }
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    /**
     * Formats a pending notification for display in an authorization prompt by
     * delegating to the provider identified by {@code providerConfigId}.
     *
     * <p>Falls back to an email-style preview if the provider config or provider
     * bean cannot be resolved.
     *
     * @param providerConfigId UUID of the {@link NotificationProviderConfig} to use
     * @param address          the destination address
     * @param title            the notification subject / headline
     * @param body             the plain-text body
     * @return a human-readable preview string (no markdown fences)
     */
    public String formatDirectNotification(String providerConfigId, String address, String title, String body) {
        log.debug("ENTER formatDirectNotification: providerConfigId={}", providerConfigId);
        try {
            NotificationProviderConfig cfg = providerConfigRepo.get(providerConfigId);
            if (cfg != null) {
                NotificationProvider provider = applicationContext
                        .getBeansOfType(NotificationProvider.class)
                        .values().stream()
                        .filter(p -> cfg.providerKey().equals(p.getProviderKey()))
                        .findFirst()
                        .orElse(null);
                if (provider != null) {
                    return provider.formatDirectNotification(address, title, body);
                }
            }
        } catch (Exception ex) {
            log.debug("formatDirectNotification: provider lookup failed, using default [error={}]", ex.getMessage());
        }
        // Default email-style fallback
        return "To: " + address + "\nSubject: " + title + "\n\n" + body;
    }
}
