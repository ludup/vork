package sh.vork.ai.security.encrypt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

class EncryptionServiceTest {

    @BeforeAll
    static void ensureBouncyCastleProviderIfPresent() {
        try {
            if (java.security.Security.getProvider("BC") == null) {
                Class<?> providerClass = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider");
                java.security.Provider provider = (java.security.Provider) providerClass.getDeclaredConstructor().newInstance();
                java.security.Security.addProvider(provider);
            }
        } catch (Exception ignored) {
            // If BC is unavailable in the test classpath, encrypt/decrypt tests will fail and report it.
        }
    }

    @AfterEach
    void clearBcProviderSideEffects() {
        // Keep provider installed for the full test run once added.
    }

    @Test
    void encryptDecrypt_roundTrip_withDefaultProvider() {
        TestProvider provider = new TestProvider("!!SFT!!", 100);
        EncryptionService service = initializedService(Map.of("software", provider));

        String plain = "secret-value";
        String encrypted = service.encrypt(plain);

        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("!!SFT!!"));
        assertTrue(service.isEncrypted(encrypted));
        assertEquals(plain, service.decrypt(encrypted));
    }

    @Test
    void encrypt_returnsInput_whenAlreadyEncrypted() {
        TestProvider provider = new TestProvider("!!SFT!!", 100);
        EncryptionService service = initializedService(Map.of("software", provider));

        String encrypted = service.encrypt("already-encrypted-check");
        assertEquals(encrypted, service.encrypt(encrypted));
    }

    @Test
    void decrypt_returnsInput_whenNotEncrypted() {
        TestProvider provider = new TestProvider("!!SFT!!", 100);
        EncryptionService service = initializedService(Map.of("software", provider));

        assertEquals("plain-text", service.decrypt("plain-text"));
    }

    @Test
    void isEncrypted_detectsKnownTag() {
        TestProvider provider = new TestProvider("!!SFT!!", 100);
        EncryptionService service = initializedService(Map.of("software", provider));

        assertTrue(service.isEncrypted("!!SFT!!payload"));
        assertFalse(service.isEncrypted("!!UNK!!payload"));
    }

    @Test
    void postConstruct_usesLowestPriorityProviderAsDefault() {
        TestProvider software = new TestProvider("!!SFT!!", 100);
        TestProvider hardware = new TestProvider("!!HSM!!", 10);

        Map<String, EncryptionProvider> providers = new LinkedHashMap<>();
        providers.put("software", software);
        providers.put("hardware", hardware);

        EncryptionService service = initializedService(providers);

        String encrypted = service.encrypt("priority-test");
        assertTrue(encrypted.startsWith("!!HSM!!"));
    }

    @Test
    void postConstruct_ignoresInvalidTagProvider() {
        TestProvider invalid = new TestProvider("BAD", 1);
        TestProvider valid = new TestProvider("!!SFT!!", 100);

        Map<String, EncryptionProvider> providers = new LinkedHashMap<>();
        providers.put("invalid", invalid);
        providers.put("valid", valid);

        EncryptionService service = initializedService(providers);

        String encrypted = service.encrypt("tag-filter-test");
        assertTrue(encrypted.startsWith("!!SFT!!"));
    }

    @Test
    void postConstruct_throwsWhenNoProviderCanBeActivated() {
        EncryptionProvider badTag = new TestProvider("BAD", 1);
        EncryptionProvider failing = new FailingInitProvider("!!HSM!!", 10);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> initializedService(Map.of("badTag", badTag, "failing", failing)));

        assertTrue(ex.getMessage().contains("No encryption provider"));
    }

    @Test
    void decrypt_throwsHelpfulError_whenProviderPayloadIsInvalid() {
        EncryptionService service = initializedService(Map.of("broken", new BrokenPayloadProvider("!!SFT!!", 100)));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.decrypt("!!SFT!!whatever"));

        assertTrue(ex.getMessage().contains("Detected likely key change"));
    }

    private EncryptionService initializedService(Map<String, EncryptionProvider> providers) {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBeansOfType(EncryptionProvider.class)).thenReturn(providers);

        EncryptionService service = new EncryptionService();
        ReflectionTestUtils.setField(service, "context", context);
        ReflectionTestUtils.invokeMethod(service, "postConstruct");
        return service;
    }

    private static class TestProvider implements EncryptionProvider {
        private final String tag;
        private final int priority;

        private TestProvider(String tag, int priority) {
            this.tag = tag;
            this.priority = priority;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public void init() throws Exception {
            // No-op for test provider.
        }

        @Override
        public String encrypt(String string) {
            return Base64.getEncoder().encodeToString(string.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String decrypt(String substring) {
            return new String(Base64.getDecoder().decode(substring), StandardCharsets.UTF_8);
        }

        @Override
        public String getTag() {
            return tag;
        }
    }

    private static final class FailingInitProvider extends TestProvider {

        private FailingInitProvider(String tag, int priority) {
            super(tag, priority);
        }

        @Override
        public void init() throws Exception {
            throw new Exception("init failed");
        }
    }

    private static final class BrokenPayloadProvider extends TestProvider {

        private BrokenPayloadProvider(String tag, int priority) {
            super(tag, priority);
        }

        @Override
        public String decrypt(String substring) {
            return "this-is-not|valid-base64|either";
        }
    }
}
