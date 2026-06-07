package sh.vork.notification.telegram;

/**
 * Implemented by any component that wishes to react to incoming Telegram
 * messages dispatched by {@link TelegramPollingService}.
 *
 * <p>All beans of this type are discovered automatically and called in
 * undefined order for every received update.
 */
public interface TelegramMessageConsumer {

    /**
     * Carries the parsed fields of a single Telegram message or callback-query update.
     *
     * @param configId        UUID of the {@code NotificationProviderConfig} whose bot received the message
     * @param botToken        bot API token (used to send replies)
     * @param chatId          Telegram chat / user ID as a string
     * @param firstName       sender's first name (may be empty)
     * @param username        sender's {@literal @}username (may be empty)
     * @param text            message text (may be {@code null} for non-text messages and callbacks)
     * @param updateId        Telegram update ID
     * @param callbackQueryId non-null when this update is a callback query (button press)
     * @param callbackData    callback_data payload sent by the pressed button (non-null when isCallback())
     */
    record IncomingMessage(
            String configId,
            String botToken,
            String chatId,
            String firstName,
            String username,
            String text,
            int    updateId,
            String callbackQueryId,
            String callbackData) {

        /** Returns {@code true} when this update is a callback query (inline keyboard button press). */
        public boolean isCallback() {
            return callbackQueryId != null;
        }
    }

    /**
     * Returns {@code true} if this consumer wants to handle the given message.
     * Called on the poller thread — must not block.
     */
    boolean accepts(IncomingMessage message);

    /**
     * Processes the message.  Called only when {@link #accepts} returned
     * {@code true}.  Exceptions are caught by the caller and logged; the
     * polling loop is not interrupted.
     */
    void process(IncomingMessage message);
}
