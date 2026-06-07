package sh.vork.notification.sendgrid;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
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
 * {@link NotificationProvider} that delivers notifications as emails via the
 * SendGrid v3 Mail Send API.
 *
 * <h3>Required settings</h3>
 * <ul>
 *   <li>{@code apiKey} — SendGrid API key (starts with {@code SG.})</li>
 *   <li>{@code fromEmail} — sender email address (must be a verified sender in SendGrid)</li>
 * </ul>
 * <h3>Optional settings</h3>
 * <ul>
 *   <li>{@code fromName} — display name shown as the sender</li>
 * </ul>
 */
@Component
public class SendGridNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SendGridNotificationProvider.class);
    private static final String SENDGRID_API_URL = "https://api.sendgrid.com/v3/mail/send";

    private static final List<SettingDefinition> DEFINITIONS = List.of(
            SettingDefinition.required("apiKey",    "API Key",        "password", "SG.xxxxxxxx"),
            SettingDefinition.required("fromEmail", "From Email",     "email",    "sender@example.com"),
            SettingDefinition.optional("fromName",  "From Name",      "text",     "Vork Notifications")
    );

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SendGridNotificationProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient   = HttpClient.newHttpClient();
    }

    @Override
    public String getProviderKey() {
        return "sendgrid";
    }

    @Override
    public String getDisplayName() {
        return "SendGrid Email";
    }

    @Override
    public Set<NotificationMediaType> getSupportedMediaTypes() {
        return Set.of(NotificationMediaType.EMAIL_ADDRESS);
    }

    @Override
    public List<SettingDefinition> getSettingDefinitions() {
        return DEFINITIONS;
    }

    @Override
    public Map<String, String> validate(Map<String, String> settings) {
        Map<String, String> errors = new LinkedHashMap<>();
        String apiKey    = settings.getOrDefault("apiKey",    "").trim();
        String fromEmail = settings.getOrDefault("fromEmail", "").trim();

        if (apiKey.isBlank()) {
            errors.put("apiKey", "API Key is required.");
        }
        if (fromEmail.isBlank()) {
            errors.put("fromEmail", "From Email is required.");
        } else if (!fromEmail.contains("@")) {
            errors.put("fromEmail", "From Email must be a valid email address.");
        }
        return errors;
    }

    @Override
    public void send(Notification notification, Map<String, String> settings) throws NotificationException {
        String apiKey    = settings.getOrDefault("apiKey",    "").trim();
        String fromEmail = settings.getOrDefault("fromEmail", "").trim();
        String fromName  = settings.getOrDefault("fromName",  "").trim();

        log.debug("ENTER send: [recipients={}, subject={}]",
                notification.recipients().size(), notification.title());

        try {
            String body = buildRequestBody(notification, fromEmail, fromName.isEmpty() ? null : fromName);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SENDGRID_API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("SendGrid API returned non-2xx status [status={}, body={}]",
                        status, response.body());
                throw new NotificationException(
                        "SendGrid returned HTTP " + status + ": " + response.body());
            }
            log.info("Notification sent via SendGrid [recipients={}, status={}]",
                    notification.recipients().size(), status);

        } catch (NotificationException e) {
            throw e;
        } catch (Exception e) {
            throw new NotificationException("Failed to send via SendGrid: " + e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildRequestBody(Notification n, String fromEmail, String fromName) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();

        // Personalizations
        List<Map<String, Object>> personalizations = new ArrayList<>();
        Map<String, Object> personalization = new LinkedHashMap<>();
        List<Map<String, String>> toList = new ArrayList<>();
        for (String recipient : n.recipients()) {
            toList.add(Map.of("email", recipient));
        }
        personalization.put("to", toList);
        personalizations.add(personalization);
        root.put("personalizations", personalizations);

        // From
        Map<String, String> from = new LinkedHashMap<>();
        from.put("email", fromEmail);
        if (fromName != null) from.put("name", fromName);
        root.put("from", from);

        root.put("subject", n.title());

        // Content
        root.put("content", List.of(Map.of("type", "text/plain", "value",
                n.body() != null && !n.body().isBlank() ? n.body() : " ")));

        // Attachment
        if (n.attachment() != null && n.attachment().length > 0) {
            String encoded  = Base64.getEncoder().encodeToString(n.attachment());
            String mimeType = n.attachmentMimeType() != null ? n.attachmentMimeType() : "application/octet-stream";
            String filename = n.attachmentFilename() != null ? n.attachmentFilename() : "attachment";
            root.put("attachments", List.of(Map.of(
                    "content",     encoded,
                    "type",        mimeType,
                    "filename",    filename,
                    "disposition", "attachment")));
        }

        return objectMapper.writeValueAsString(root);
    }
}
