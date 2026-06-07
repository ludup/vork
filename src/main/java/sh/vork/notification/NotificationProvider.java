package sh.vork.notification;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SPI for pluggable notification delivery channels.
 *
 * <p>Implementations are discovered as Spring beans.  Each implementation is
 * identified by a unique {@link #getProviderKey()} and declares its required
 * configuration fields via {@link #getSettingDefinitions()}.  Saved configurations
 * are persisted as {@link NotificationProviderConfig} documents in MongoDB and
 * passed back to {@link #send} at delivery time.
 *
 * <p>Example implementations: SendGrid, SMTP, Slack webhook.
 */
public interface NotificationProvider {

    /**
     * Stable machine identifier for this provider type (e.g. {@code "sendgrid"}).
     * Must be unique across all registered providers.
     */
    String getProviderKey();

    /**
     * Human-readable name shown in the settings UI (e.g. {@code "SendGrid Email"}).
     */
    String getDisplayName();

    /**
     * The media type(s) that recipient addresses must be expressed as for this
     * provider (e.g. {@link NotificationMediaType#EMAIL_ADDRESS} for email providers,
     * {@link NotificationMediaType#PHONE_NUMBER} for SMS providers).
     */
    Set<NotificationMediaType> getSupportedMediaTypes();

    /**
     * Ordered list of configuration fields operators must fill in when adding
     * an instance of this provider.
     */
    List<SettingDefinition> getSettingDefinitions();

    /**
     * Validates the supplied settings map.
     *
     * @param settings the operator-supplied values keyed by {@link SettingDefinition#key()}
     * @return a map of {@code fieldKey → error message} for any invalid fields;
     *         an empty map means the settings are valid
     */
    Map<String, String> validate(Map<String, String> settings);

    /**
     * Dispatches a notification using the provider-specific channel.
     *
     * @param notification the notification payload to deliver
     * @param settings     the persisted configuration values for this provider instance
     * @throws NotificationException if delivery fails
     */
    void send(Notification notification, Map<String, String> settings) throws NotificationException;

    /**
     * Whether this provider can deliver to an arbitrary address that has not
     * previously registered with the service.
     *
     * <p>Email and SMS providers return {@code true} because they can send to
     * any well-formed address.  Providers that require prior opt-in (e.g. Telegram,
     * where the recipient must first message the bot to obtain a chat ID) should
     * override this method to return {@code false}.
     *
     * @return {@code true} if the provider accepts unregistered addresses
     */
    default boolean supportsDirectAddress() {
        return true;
    }

    /**
     * Formats a pending notification for display in the AI authorization prompt.
     *
     * <p>The default implementation renders an email-style preview.  Providers
     * that deliver via a different channel (e.g. SMS) should override this to
     * produce a layout appropriate for their medium.
     *
     * @param address the destination address
     * @param title   the notification subject / headline
     * @param body    the plain-text body
     * @return a human-readable preview string (no markdown fences — callers apply those)
     */
    default String formatDirectNotification(String address, String title, String body) {
        return "To: " + address + "\nSubject: " + title + "\n\n" + body;
    }
}
