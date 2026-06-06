package sh.vork.notification.smtp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import sh.vork.notification.Notification;
import sh.vork.notification.NotificationException;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProvider;
import sh.vork.notification.SettingDefinition;

/**
 * {@link NotificationProvider} that delivers notifications as emails via a
 * user-supplied SMTP server using Spring's {@link JavaMailSenderImpl}.
 *
 * <h3>Required settings</h3>
 * <ul>
 *   <li>{@code host}      — SMTP server hostname</li>
 *   <li>{@code fromEmail} — sender address (must be authorised on the server)</li>
 * </ul>
 * <h3>Optional settings</h3>
 * <ul>
 *   <li>{@code port}     — SMTP port (default {@code 587})</li>
 *   <li>{@code username} — SMTP auth username</li>
 *   <li>{@code password} — SMTP auth password</li>
 *   <li>{@code fromName} — display name shown as the sender</li>
 *   <li>{@code startTls} — {@code "true"} to enable STARTTLS (default {@code "true"})</li>
 * </ul>
 */
@Component
public class SmtpNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SmtpNotificationProvider.class);

    private static final List<SettingDefinition> DEFINITIONS = List.of(
            SettingDefinition.required("host",      "SMTP Host",    "text",     "mail.example.com"),
            SettingDefinition.optional("port",      "SMTP Port",    "text",     "587"),
            SettingDefinition.required("fromEmail", "From Email",   "email",    "sender@example.com"),
            SettingDefinition.optional("fromName",  "From Name",    "text",     "Vork Notifications"),
            SettingDefinition.optional("username",  "Username",     "text",     "user@example.com"),
            SettingDefinition.optional("password",  "Password",     "password", ""),
            SettingDefinition.optional("startTls",  "Use STARTTLS", "text",     "true")
    );

    @Override
    public String getProviderKey() { return "smtp"; }

    @Override
    public String getDisplayName() { return "SMTP Email"; }

    @Override
    public Set<NotificationMediaType> getSupportedMediaTypes() {
        return Set.of(NotificationMediaType.EMAIL_ADDRESS);
    }

    @Override
    public List<SettingDefinition> getSettingDefinitions() { return DEFINITIONS; }

    @Override
    public Map<String, String> validate(Map<String, String> settings) {
        Map<String, String> errors = new LinkedHashMap<>();
        String host      = settings.getOrDefault("host",      "").trim();
        String fromEmail = settings.getOrDefault("fromEmail", "").trim();
        String port      = settings.getOrDefault("port",      "").trim();

        if (host.isBlank())      errors.put("host",      "SMTP Host is required.");
        if (fromEmail.isBlank()) errors.put("fromEmail", "From Email is required.");
        else if (!fromEmail.contains("@")) errors.put("fromEmail", "From Email must be a valid email address.");
        if (!port.isBlank()) {
            try {
                int p = Integer.parseInt(port);
                if (p < 1 || p > 65535) errors.put("port", "Port must be between 1 and 65535.");
            } catch (NumberFormatException e) {
                errors.put("port", "Port must be a number.");
            }
        }
        return errors;
    }

    @Override
    public void send(Notification notification, Map<String, String> settings) throws NotificationException {
        String host      = settings.getOrDefault("host",      "").trim();
        String portStr   = settings.getOrDefault("port",      "587").trim();
        String fromEmail = settings.getOrDefault("fromEmail", "").trim();
        String fromName  = settings.getOrDefault("fromName",  "").trim();
        String username  = settings.getOrDefault("username",  "").trim();
        String password  = settings.getOrDefault("password",  "").trim();
        String startTls  = settings.getOrDefault("startTls",  "true").trim();

        log.debug("ENTER send: [host={}, port={}, recipients={}, subject={}]",
                host, portStr, notification.recipients().size(), notification.title());

        int  port       = portStr.isBlank() ? 587 : Integer.parseInt(portStr);
        boolean useTls  = !"false".equalsIgnoreCase(startTls);
        boolean useAuth = !username.isBlank();

        JavaMailSenderImpl sender = buildSender(host, port, useTls, useAuth, username, password);

        try {
            for (String recipient : notification.recipients()) {
                sendOne(sender, fromEmail, fromName, recipient,
                        notification.title(),
                        notification.body() != null ? notification.body() : "");
                log.debug("Step: email sent [to={}, subject={}]", recipient, notification.title());
            }
            log.info("Notification sent via SMTP [recipients={}, host={}]",
                    notification.recipients().size(), host);
        } catch (MessagingException e) {
            throw new NotificationException("Failed to send via SMTP: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new NotificationException("Failed to send via SMTP: " + e.getMessage(), e);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private JavaMailSenderImpl buildSender(String host, int port, boolean useTls,
                                           boolean useAuth, String username, String password) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol",     "smtp");
        props.put("mail.smtp.auth",              String.valueOf(useAuth));
        props.put("mail.smtp.starttls.enable",   String.valueOf(useTls));
        props.put("mail.smtp.starttls.required", String.valueOf(useTls));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout",           "10000");
        props.put("mail.smtp.writetimeout",      "10000");

        if (useAuth) {
            sender.setUsername(username);
            sender.setPassword(password);
        }
        return sender;
    }

    private void sendOne(JavaMailSenderImpl sender, String fromEmail, String fromName,
                         String recipient, String subject, String body) throws MessagingException {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setFrom(fromName.isBlank() ? fromEmail : fromName + " <" + fromEmail + ">");
        helper.setTo(recipient);
        helper.setSubject(subject);
        helper.setText(body, false);
        sender.send(message);
    }
}
