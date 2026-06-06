package sh.vork.notification.user;

import com.jadaptive.orm.DatabaseEntity;
import sh.vork.notification.NotificationMediaType;

/**
 * Stores a single notification delivery address for a user.
 *
 * <p>Each user may hold multiple addresses across different media types and
 * providers.  At most one entry per user has {@link #isDefault} set to
 * {@code true}; that entry is used when no specific address is requested.
 *
 * @param uuid        surrogate UUID
 * @param userId      the username (matches {@code VorkUser.uuid})
 * @param providerKey the provider that handles this address (e.g. {@code "sendgrid"})
 * @param mediaType   the type of address ({@link NotificationMediaType})
 * @param address     the actual address: email, E.164 phone, Telegram handle/ID
 * @param label       optional friendly label (e.g. "Work email")
 * @param isDefault   {@code true} when this is the user's preferred delivery address
 * @param createdAt   epoch-millis creation timestamp
 */
public record UserNotificationMedia(
        String               uuid,
        String               userId,
        String               providerKey,
        NotificationMediaType mediaType,
        String               address,
        String               label,
        boolean              isDefault,
        long                 createdAt
) implements DatabaseEntity {}
