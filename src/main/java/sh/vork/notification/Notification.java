package sh.vork.notification;

import java.util.List;

/**
 * An immutable notification payload to be dispatched via one or more
 * {@link NotificationProvider} implementations.
 *
 * @param recipients       addresses/usernames of the intended recipients
 * @param title            subject / headline of the notification
 * @param body             plain-text body content
 * @param attachment       optional binary attachment (may be {@code null})
 * @param attachmentFilename filename shown to the recipient (e.g. {@code "report.pdf"})
 * @param attachmentMimeType MIME type of the attachment (e.g. {@code "application/pdf"})
 */
public record Notification(
        List<String> recipients,
        String       title,
        String       body,
        byte[]       attachment,
        String       attachmentFilename,
        String       attachmentMimeType
) {
    public Notification {
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("At least one recipient is required.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title must not be blank.");
        }
        if (body == null) {
            body = "";
        }
    }

    /** Convenience factory — no attachment. */
    public static Notification of(List<String> recipients, String title, String body) {
        return new Notification(recipients, title, body, null, null, null);
    }
}
