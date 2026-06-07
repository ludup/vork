package sh.vork.notification.telegram;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.notification.Notification;
import sh.vork.notification.NotificationException;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProvider;
import sh.vork.notification.SettingDefinition;

/**
 * {@link NotificationProvider} that delivers notifications as messages via the
 * Telegram Bot API.
 *
 * <p>Recipients in the {@link Notification} are treated as Telegram chat IDs
 * (numeric user/group IDs or {@code @username} handles).
 *
 * <h3>Required settings</h3>
 * <ul>
 *   <li>{@code botToken} — the HTTP API token issued by {@literal @}BotFather</li>
 * </ul>
 */
@Component
public class TelegramNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationProvider.class);
    private static final String TELEGRAM_API_BASE = "https://api.telegram.org/bot%s/sendMessage";

    private static final List<SettingDefinition> DEFINITIONS = List.of(
            SettingDefinition.required("botToken", "Bot Token", "password", "123456:ABC-DEF1234...")
    );

    private final ObjectMapper objectMapper;
    private final HttpClient   httpClient;

    public TelegramNotificationProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient   = HttpClient.newHttpClient();
    }

    @Override
    public String getProviderKey() {
        return "telegram";
    }

    @Override
    public String getDisplayName() {
        return "Telegram";
    }

    @Override
    public Set<NotificationMediaType> getSupportedMediaTypes() {
        return Set.of(NotificationMediaType.TELEGRAM);
    }

    /**
     * Telegram requires the recipient to have previously messaged the bot to
     * obtain a chat ID — arbitrary addresses cannot be reached without prior
     * opt-in.
     */
    @Override
    public boolean supportsDirectAddress() {
        return false;
    }

    @Override
    public List<SettingDefinition> getSettingDefinitions() {
        return DEFINITIONS;
    }

    @Override
    public Map<String, String> validate(Map<String, String> settings) {
        Map<String, String> errors = new LinkedHashMap<>();
        String botToken = settings.getOrDefault("botToken", "").trim();
        if (botToken.isBlank()) {
            errors.put("botToken", "Bot Token is required.");
        }
        return errors;
    }

    @Override
    public void send(Notification notification, Map<String, String> settings) throws NotificationException {
        String botToken = settings.getOrDefault("botToken", "").trim();
        String url      = String.format(TELEGRAM_API_BASE, botToken);

        log.debug("ENTER send: [recipients={}, title={}]",
                notification.recipients().size(), notification.title());

        // Build message text: bold title + body
        String text = "*" + escapeMarkdown(notification.title()) + "*"
                + (notification.body() != null && !notification.body().isBlank()
                        ? "\n" + escapeMarkdown(notification.body())
                        : "");

        try {
            for (String chatId : notification.recipients()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("chat_id",    chatId);
                payload.put("text",       text);
                payload.put("parse_mode", "MarkdownV2");

                String body = objectMapper.writeValueAsString(payload);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    log.warn("Telegram API returned non-2xx [status={}, body={}]", status, response.body());
                    throw new NotificationException("Telegram returned HTTP " + status + ": " + response.body());
                }
                log.debug("Step: message sent via Telegram [chatId={}, status={}]", chatId, status);
            }
            log.info("Notification sent via Telegram [recipients={}]", notification.recipients().size());

        } catch (NotificationException e) {
            throw e;
        } catch (Exception e) {
            throw new NotificationException("Failed to send via Telegram: " + e.getMessage(), e);
        }
    }

    /** Escape special characters required by Telegram MarkdownV2 formatting. */
    private static String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("_",  "\\_")
                   .replace("*",  "\\*")
                   .replace("[",  "\\[")
                   .replace("]",  "\\]")
                   .replace("(",  "\\(")
                   .replace(")",  "\\)")
                   .replace("~",  "\\~")
                   .replace("`",  "\\`")
                   .replace(">",  "\\>")
                   .replace("#",  "\\#")
                   .replace("+",  "\\+")
                   .replace("-",  "\\-")
                   .replace("=",  "\\=")
                   .replace("|",  "\\|")
                   .replace("{",  "\\{")
                   .replace("}",  "\\}")
                   .replace(".",  "\\.")
                   .replace("!",  "\\!");
    }
}
