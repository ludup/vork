package sh.vork.notification.twilio;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import sh.vork.notification.Notification;
import sh.vork.notification.NotificationException;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProvider;
import sh.vork.notification.SettingDefinition;

/**
 * {@link NotificationProvider} that delivers notifications as SMS messages via
 * the Twilio Programmable Messaging REST API.
 *
 * <p>Recipients in the {@link Notification} are treated as destination phone
 * numbers in E.164 format (e.g. {@code +14155552671}).
 *
 * <h3>Required settings</h3>
 * <ul>
 *   <li>{@code accountSid} — Twilio Account SID (starts with {@code AC})</li>
 *   <li>{@code authToken}  — Twilio Auth Token</li>
 *   <li>{@code fromNumber} — Twilio phone number or messaging service SID</li>
 * </ul>
 */
@Component
public class TwilioSmsNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsNotificationProvider.class);
    private static final String TWILIO_API_BASE = "https://api.twilio.com/2010-04-01/Accounts/%s/Messages.json";

    private static final List<SettingDefinition> DEFINITIONS = List.of(
            SettingDefinition.required("accountSid",  "Account SID",   "text",     "ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"),
            SettingDefinition.required("authToken",   "Auth Token",    "password", ""),
            SettingDefinition.required("fromNumber",  "From Number",   "text",     "+14155552671")
    );

    private final HttpClient httpClient;

    public TwilioSmsNotificationProvider() {
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String getProviderKey() {
        return "twilio-sms";
    }

    @Override
    public String getDisplayName() {
        return "Twilio SMS";
    }

    @Override
    public Set<NotificationMediaType> getSupportedMediaTypes() {
        return Set.of(NotificationMediaType.PHONE_NUMBER);
    }

    @Override
    public List<SettingDefinition> getSettingDefinitions() {
        return DEFINITIONS;
    }

    @Override
    public Map<String, String> validate(Map<String, String> settings) {
        Map<String, String> errors = new LinkedHashMap<>();
        String accountSid = settings.getOrDefault("accountSid", "").trim();
        String authToken  = settings.getOrDefault("authToken",  "").trim();
        String fromNumber = settings.getOrDefault("fromNumber", "").trim();

        if (accountSid.isBlank()) {
            errors.put("accountSid", "Account SID is required.");
        }
        if (authToken.isBlank()) {
            errors.put("authToken", "Auth Token is required.");
        }
        if (fromNumber.isBlank()) {
            errors.put("fromNumber", "From Number is required.");
        }
        return errors;
    }

    @Override
    public void send(Notification notification, Map<String, String> settings) throws NotificationException {
        String accountSid = settings.getOrDefault("accountSid", "").trim();
        String authToken  = settings.getOrDefault("authToken",  "").trim();
        String fromNumber = settings.getOrDefault("fromNumber", "").trim();

        log.debug("ENTER send: [from={}, recipients={}, title={}]",
                fromNumber, notification.recipients().size(), notification.title());

        String credentials = Base64.getEncoder().encodeToString(
                (accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));

        String url = String.format(TWILIO_API_BASE, accountSid);
        // SMS body: combine title and body
        String smsBody = notification.title() + "\n" + notification.body();

        try {
            for (String recipient : notification.recipients()) {
                String formBody = "To="    + URLEncoder.encode(recipient,  StandardCharsets.UTF_8)
                        + "&From=" + URLEncoder.encode(fromNumber, StandardCharsets.UTF_8)
                        + "&Body=" + URLEncoder.encode(smsBody,    StandardCharsets.UTF_8);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Basic " + credentials)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    log.warn("Twilio API returned non-2xx [status={}, body={}]", status, response.body());
                    throw new NotificationException("Twilio returned HTTP " + status + ": " + response.body());
                }
                log.debug("Step: SMS sent via Twilio [to={}, status={}]", recipient, status);
            }
            log.info("Notification sent via Twilio SMS [recipients={}]", notification.recipients().size());

        } catch (NotificationException e) {
            throw e;
        } catch (Exception e) {
            throw new NotificationException("Failed to send via Twilio: " + e.getMessage(), e);
        }
    }

    @Override
    public String formatDirectNotification(String address, String title, String body) {
        return "To: " + address + "\n\n" + title + ": " + body;
    }
}
