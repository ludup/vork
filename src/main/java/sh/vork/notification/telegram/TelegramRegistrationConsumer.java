package sh.vork.notification.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link TelegramMessageConsumer} that handles the QR-code registration flow.
 */
@Component
public class TelegramRegistrationConsumer implements TelegramMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelegramRegistrationConsumer.class);

    private final TelegramRegistrationService registrationService;
    private final TelegramApiClient           telegramApiClient;

    public TelegramRegistrationConsumer(TelegramRegistrationService registrationService,
                                         TelegramApiClient telegramApiClient) {
        this.registrationService = registrationService;
        this.telegramApiClient   = telegramApiClient;
    }

    @Override
    public boolean accepts(IncomingMessage message) {
        if (message.isCallback()) return false;
        String text = message.text();
        return text != null && (text.startsWith("/start ") || text.equals("/start"));
    }

    @Override
    public void process(IncomingMessage message) {
        log.debug("ENTER process: [chatId={}, text={}]", message.chatId(), message.text());

        String text = message.text().trim();
        String code = text.length() > 7 ? text.substring(7).trim() : "";

        if (code.isBlank()) {
            telegramApiClient.sendText(message.botToken(), message.chatId(),
                    "Hi! Please scan the QR code in the Vork app to link your Telegram account.");
            return;
        }

        boolean completed = registrationService.complete(code, message.chatId(), message.firstName());

        if (completed) {
            String name = (message.firstName() != null && !message.firstName().isBlank())
                    ? message.firstName() : "there";
            telegramApiClient.sendText(message.botToken(), message.chatId(),
                    "\u2705 Success! Hi " + name + ", your Telegram account is now linked to Vork.");
            log.info("Registration completed [chatId={}, firstName={}]",
                    message.chatId(), message.firstName());
        } else {
            telegramApiClient.sendText(message.botToken(), message.chatId(),
                    "\u274c This registration link has expired or is invalid. "
                    + "Please request a new one from the Vork app.");
            log.warn("Registration code not found or expired [code={}, chatId={}]",
                    code, message.chatId());
        }
    }
}
