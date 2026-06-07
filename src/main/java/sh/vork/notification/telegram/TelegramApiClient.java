package sh.vork.notification.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Thin wrapper around the Telegram Bot HTTP API.
 *
 * <p>All methods are fire-and-forget: errors are logged but not rethrown
 * so that a Telegram API hiccup never crashes the caller's flow.
 */
@Service
public class TelegramApiClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramApiClient.class);
    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final int    MAX_MSG_LEN = 4096;
    private static final int    MAX_CB_TEXT = 200;

    /** A single button in an inline keyboard row. */
    public record InlineButton(String text, String callbackData) {}

    private final ObjectMapper objectMapper;
    private final HttpClient   httpClient;

    public TelegramApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Sends a plain-text message to the given chat. */
    public void sendText(String botToken, String chatId, String text) {
        post(botToken, "sendMessage",
                Map.of("chat_id", chatId, "text", truncate(text, MAX_MSG_LEN)));
    }

    /** Sends a MarkdownV2-formatted message. */
    public void sendTextMarkdownV2(String botToken, String chatId, String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id",    chatId);
        payload.put("text",       truncate(text, MAX_MSG_LEN));
        payload.put("parse_mode", "MarkdownV2");
        post(botToken, "sendMessage", payload);
    }

    /**
     * Sends a message with an inline keyboard.
     *
     * @param keyboard list of rows, each row is a list of {@link InlineButton}s
     */
    public void sendWithInlineKeyboard(String botToken, String chatId, String text,
                                        List<List<InlineButton>> keyboard) {
        List<List<Map<String, String>>> tgKeyboard = keyboard.stream()
                .map(row -> row.stream()
                        .map(btn -> Map.of("text", btn.text(), "callback_data", btn.callbackData()))
                        .toList())
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id",      chatId);
        payload.put("text",         truncate(text, MAX_MSG_LEN));
        payload.put("reply_markup", Map.of("inline_keyboard", tgKeyboard));
        post(botToken, "sendMessage", payload);
    }

    /** Sends a MarkdownV2-formatted message with an inline keyboard. */
    public void sendWithInlineKeyboardMarkdownV2(String botToken, String chatId, String text,
                                                  List<List<InlineButton>> keyboard) {
        List<List<Map<String, String>>> tgKeyboard = keyboard.stream()
                .map(row -> row.stream()
                        .map(btn -> Map.of("text", btn.text(), "callback_data", btn.callbackData()))
                        .toList())
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id",      chatId);
        payload.put("text",         truncate(text, MAX_MSG_LEN));
        payload.put("parse_mode",   "MarkdownV2");
        payload.put("reply_markup", Map.of("inline_keyboard", tgKeyboard));
        post(botToken, "sendMessage", payload);
    }

    /**
     * Acknowledges a callback query (clears the loading spinner on the button).
     *
     * @param notificationText optional brief popup text shown to the user (max 200 chars)
     */
    public void answerCallbackQuery(String botToken, String callbackQueryId, String notificationText) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callback_query_id", callbackQueryId);
        if (notificationText != null && !notificationText.isBlank()) {
            payload.put("text", truncate(notificationText, MAX_CB_TEXT));
        }
        post(botToken, "answerCallbackQuery", payload);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void post(String botToken, String method, Object payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + botToken + "/" + method))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("Telegram {} returned HTTP {} [body={}]", method, resp.statusCode(),
                        truncate(resp.body(), 300));
            }
        } catch (Exception e) {
            log.warn("Telegram {} call failed: {}", method, e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
