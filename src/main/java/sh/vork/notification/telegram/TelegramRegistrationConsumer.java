package sh.vork.notification.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link TelegramMessageConsumer} that handles the QR-code registration flow.
 *
 * <p>Accepts any message whose text starts with {@code /start} and attempts
 * to complete a pending registration using the code that follows the command.
 * Sends a confirmation (or failure) reply back to the user's chat.
 */
@Component
public class TelegramRegistrationConsumer implements TelegramMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelegramRegistrationConsumer.class);
    private static final String API_BASE = "https://api.telegram.org/bot";

    private final TelegramRegistrationService registrationService;
    private final ObjectMapper objectMapper;
    private final HttpClient   httpClient;

    public TelegramRegistrationConsumer(TelegramRegistrationService registrationService,
                                         ObjectMapper objectMapper) {
        this.registrationService = registrationService;
        this.objectMapper        = objectMapper;
        this.httpClient          = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── TelegramMessageConsumer ───────────────────────────────────────────────

    @Override
    public boolean accepts(IncomingMessage message) {
        String text = message.text();
        return text != null && (text.startsWith("/start ") || text.equals("/start"));
    }

    @Override
    public void process(IncomingMessage message) {
        log.debug("ENTER process: [chatId={}, text={}]", message.chatId(), message.text());

        String text = message.text().trim();
        // "/start CODE" or plain "/start" (no code)
        String code = text.length() > 7 ? text.substring(7).trim() : "";

        if (code.isBlank()) {
            log.debug("Received /start without code — ignoring [chatId={}]", message.chatId());
            sendReply(message.botToken(), message.chatId(),
                    "Hi! Please scan the QR code in the Vork app to link your Telegram account.");
            return;
        }

        boolean completed = registrationService.complete(code, message.chatId(), message.firstName());

        if (completed) {
            String name = message.firstName() != null && !message.firstName().isBlank()
                    ? message.firstName() : "there";
            sendReply(message.botToken(), message.chatId(),
                    "✅ Success! Hi " + name + ", your Telegram account is now linked to Vork.");
            log.info("Registration completed [chatId={}, firstName={}]",
                    message.chatId(), message.firstName());
        } else {
            sendReply(message.botToken(), message.chatId(),
                    "❌ This registration link has expired or is invalid. "
                    + "Please request a new one from the Vork app.");
            log.warn("Registration code not found or expired [code={}, chatId={}]",
                    code, message.chatId());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void sendReply(String botToken, String chatId, String text) {
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("chat_id", chatId, "text", text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + botToken + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("Failed to send reply [chatId={}, error={}]", chatId, e.getMessage());
        }
    }
}
