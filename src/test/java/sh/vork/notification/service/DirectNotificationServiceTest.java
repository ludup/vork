package sh.vork.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import com.jadaptive.orm.mock.MapDatabaseRepository;

import sh.vork.notification.Notification;
import sh.vork.notification.NotificationException;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProvider;
import sh.vork.notification.NotificationProviderConfig;
import sh.vork.notification.service.DirectNotificationService.ProviderSummary;

class DirectNotificationServiceTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static NotificationProvider emailProvider(String key, boolean direct) {
        NotificationProvider p = mock(NotificationProvider.class);
        when(p.getProviderKey()).thenReturn(key);
        when(p.getSupportedMediaTypes()).thenReturn(Set.of(NotificationMediaType.EMAIL_ADDRESS));
        when(p.supportsDirectAddress()).thenReturn(direct);
        return p;
    }

    private static NotificationProvider smsProvider(String key) {
        NotificationProvider p = mock(NotificationProvider.class);
        when(p.getProviderKey()).thenReturn(key);
        when(p.getSupportedMediaTypes()).thenReturn(Set.of(NotificationMediaType.PHONE_NUMBER));
        when(p.supportsDirectAddress()).thenReturn(true);
        return p;
    }

    private static NotificationProvider telegramProvider() {
        NotificationProvider p = mock(NotificationProvider.class);
        when(p.getProviderKey()).thenReturn("telegram");
        when(p.getSupportedMediaTypes()).thenReturn(Set.of(NotificationMediaType.TELEGRAM));
        when(p.supportsDirectAddress()).thenReturn(false);
        return p;
    }

    private static NotificationProviderConfig config(String uuid, String providerKey, String displayName) {
        return new NotificationProviderConfig(uuid, providerKey, displayName, Map.of("key", "value"));
    }

    // ── Discovery tests ───────────────────────────────────────────────────────

    @Nested
    class ListAvailableTests {

        @Test
        void returnsConfiguredDirectAddressProviders() {
            var sendgrid = emailProvider("sendgrid", true);
            var twilio   = smsProvider("twilio-sms");

            var repo = new MapDatabaseRepository<>(NotificationProviderConfig.class);
            String sgId  = UUID.randomUUID().toString();
            String twId  = UUID.randomUUID().toString();
            repo.save(config(sgId, "sendgrid", "SendGrid Email"));
            repo.save(config(twId, "twilio-sms", "Twilio SMS"));

            ApplicationContext ctx = mock(ApplicationContext.class);
            when(ctx.getBeansOfType(NotificationProvider.class))
                    .thenReturn(Map.of("sendgrid", sendgrid, "twilio-sms", twilio));

            var service = new DirectNotificationService(repo, ctx);
            List<ProviderSummary> result = service.listAvailable();

            assertEquals(2, result.size());
            assertTrue(result.stream().anyMatch(s -> s.configId().equals(sgId)
                    && s.mediaTypes().contains(NotificationMediaType.EMAIL_ADDRESS)));
            assertTrue(result.stream().anyMatch(s -> s.configId().equals(twId)
                    && s.mediaTypes().contains(NotificationMediaType.PHONE_NUMBER)));
        }

        @Test
        void excludesTelegramProvider() {
            var telegram = telegramProvider();

            var repo = new MapDatabaseRepository<>(NotificationProviderConfig.class);
            repo.save(config(UUID.randomUUID().toString(), "telegram", "Telegram"));

            ApplicationContext ctx = mock(ApplicationContext.class);
            when(ctx.getBeansOfType(NotificationProvider.class))
                    .thenReturn(Map.of("telegram", telegram));

            var service = new DirectNotificationService(repo, ctx);
            List<ProviderSummary> result = service.listAvailable();

            assertTrue(result.isEmpty(), "Telegram should be excluded from direct-address list");
        }

        @Test
        void excludesProviderWithNoSavedConfig() {
            var sendgrid = emailProvider("sendgrid", true);
            // No config saved for sendgrid

            var repo = new MapDatabaseRepository<>(NotificationProviderConfig.class);

            ApplicationContext ctx = mock(ApplicationContext.class);
            when(ctx.getBeansOfType(NotificationProvider.class))
                    .thenReturn(Map.of("sendgrid", sendgrid));

            var service = new DirectNotificationService(repo, ctx);
            assertTrue(service.listAvailable().isEmpty(),
                    "Provider without saved config should not appear in list");
        }

        @Test
        void returnsEmptyWhenNoProvidersRegistered() {
            var repo = new MapDatabaseRepository<>(NotificationProviderConfig.class);
            ApplicationContext ctx = mock(ApplicationContext.class);
            when(ctx.getBeansOfType(NotificationProvider.class)).thenReturn(Map.of());

            var service = new DirectNotificationService(repo, ctx);
            assertTrue(service.listAvailable().isEmpty());
        }
    }

    // ── Delivery tests ────────────────────────────────────────────────────────

    @Nested
    class SendTests {

        private MapDatabaseRepository<NotificationProviderConfig> repo;
        private NotificationProvider sendgrid;
        private NotificationProvider twilio;
        private ApplicationContext ctx;
        private String sgConfigId;

        @BeforeEach
        void setUp() {
            sendgrid = emailProvider("sendgrid", true);
            twilio   = smsProvider("twilio-sms");
            repo     = new MapDatabaseRepository<>(NotificationProviderConfig.class);

            sgConfigId = UUID.randomUUID().toString();
            repo.save(config(sgConfigId, "sendgrid", "SendGrid Email"));

            ctx = mock(ApplicationContext.class);
            when(ctx.getBeansOfType(NotificationProvider.class))
                    .thenReturn(Map.of("sendgrid", sendgrid, "twilio-sms", twilio));
        }

        @Test
        void sendsViaCorrectProvider() throws Exception {
            var service = new DirectNotificationService(repo, ctx);
            String result = service.send(sgConfigId, "Hello", "World", "user@example.com");

            assertEquals("ok", result);
            verify(sendgrid).send(any(Notification.class), eq(Map.of("key", "value")));
            verify(twilio, never()).send(any(), any());
        }

        @Test
        void passesCorrectRecipientAndContent() throws Exception {
            var service = new DirectNotificationService(repo, ctx);
            service.send(sgConfigId, "My Title", "My Body", "target@test.com");

            var captor = org.mockito.ArgumentCaptor.forClass(Notification.class);
            verify(sendgrid).send(captor.capture(), any());

            Notification sent = captor.getValue();
            assertEquals(List.of("target@test.com"), sent.recipients());
            assertEquals("My Title", sent.title());
            assertEquals("My Body", sent.body());
        }

        @Test
        void returnsErrorForUnknownConfigId() throws Exception {
            var service = new DirectNotificationService(repo, ctx);
            String result = service.send("non-existent-uuid", "Hi", "Body", "x@y.com");

            assertTrue(result.startsWith("error:"), "Expected error string, got: " + result);
            verify(sendgrid, never()).send(any(), any());
        }

        @Test
        void returnsErrorWhenProviderThrows() throws Exception {
            org.mockito.Mockito.doThrow(new NotificationException("API down")).when(sendgrid).send(any(), any());

            var service = new DirectNotificationService(repo, ctx);
            String result = service.send(sgConfigId, "Hi", "Body", "x@y.com");

            assertTrue(result.startsWith("error:"), "Expected error string, got: " + result);
        }

        @Test
        void returnsErrorWhenProviderBeanMissing() throws Exception {
            // Config exists for a providerKey that has no registered Spring bean
            String orphanId = UUID.randomUUID().toString();
            repo.save(config(orphanId, "unknown-provider", "Ghost"));

            var service = new DirectNotificationService(repo, ctx);
            String result = service.send(orphanId, "Hi", "Body", "x@y.com");

            assertTrue(result.startsWith("error:"), "Expected error for missing provider bean");
        }

        @Test
        void returnsErrorIfProviderDoesNotSupportDirectAddress() throws Exception {
            var telegram = telegramProvider();
            String tgId = UUID.randomUUID().toString();
            repo.save(config(tgId, "telegram", "Telegram"));

            when(ctx.getBeansOfType(NotificationProvider.class))
                    .thenReturn(Map.of("sendgrid", sendgrid, "telegram", telegram));

            var service = new DirectNotificationService(repo, ctx);
            String result = service.send(tgId, "Hi", "Body", "@someuser");

            assertTrue(result.startsWith("error:"), "Expected error for non-direct provider");
        }
    }
}
