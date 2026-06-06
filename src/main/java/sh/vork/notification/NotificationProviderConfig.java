package sh.vork.notification;

import com.jadaptive.orm.DatabaseEntity;

import java.util.Map;

/**
 * Persisted configuration for one instance of a {@link NotificationProvider}.
 *
 * <p>An operator may configure multiple instances of the same provider type
 * (e.g. two SendGrid accounts for different sender addresses).  Each instance
 * has a user-assigned {@link #displayName} so it can be identified in the UI.
 *
 * @param uuid        MongoDB {@code _id}
 * @param providerKey identifies the {@link NotificationProvider} bean
 *                    (matches {@link NotificationProvider#getProviderKey()})
 * @param displayName user-assigned label (e.g. {@code "Alerts Email"})
 * @param settings    provider-specific key/value configuration (e.g. API key,
 *                    from-address).  Stored as a plain map; password-type fields
 *                    are never returned to the browser verbatim.
 */
public record NotificationProviderConfig(
        String              uuid,
        String              providerKey,
        String              displayName,
        Map<String, String> settings
) implements DatabaseEntity {

    public NotificationProviderConfig {
        if (displayName == null || displayName.isBlank()) {
            displayName = providerKey;
        }
        if (settings == null) {
            settings = Map.of();
        }
    }
}
