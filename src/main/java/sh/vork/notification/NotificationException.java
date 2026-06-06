package sh.vork.notification;

/**
 * Thrown when a {@link NotificationProvider} fails to dispatch a notification.
 */
public class NotificationException extends Exception {

    public NotificationException(String message) {
        super(message);
    }

    public NotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
