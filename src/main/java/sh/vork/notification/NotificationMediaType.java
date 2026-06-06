package sh.vork.notification;

/**
 * Classifies the type of address a {@link NotificationProvider} expects for its
 * recipients — used in the settings UI to indicate which kind of address to
 * collect from the operator.
 */
public enum NotificationMediaType {

    /** RFC 5321 email address, e.g. {@code user@example.com}. */
    EMAIL_ADDRESS,

    /** E.164 phone number, e.g. {@code +14155552671}. */
    PHONE_NUMBER,

    /** Telegram chat identifier (numeric ID or {@code @username} handle). */
    TELEGRAM
}
