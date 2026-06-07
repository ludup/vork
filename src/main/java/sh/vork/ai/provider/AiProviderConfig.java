package sh.vork.ai.provider;

import com.jadaptive.orm.DatabaseEntity;

/**
 * Persisted configuration for a single AI provider (API key, base URL, default model).
 *
 * <p>The {@code uuid} doubles as the stable primary key and is set to the
 * lower-cased provider name (e.g. {@code "openai"}, {@code "ollama"},
 * {@code "gemini"}) so configs can be retrieved with a single
 * {@code repo.get(provider.name().toLowerCase())} call.
 *
 * <p>API keys are encrypted at rest by {@link sh.vork.ai.security.encrypt.EncryptionService}
 * before being persisted.  The {@code apiKey} field on a loaded record contains the encrypted
 * form; callers that need the plaintext key must go through
 * {@link AiProviderConfigService#decryptApiKey(String)}.
 */
public record AiProviderConfig(
        String uuid,
        String provider,
        String apiKey,
        String baseUrl,
        boolean enabled,
        String defaultModel
) implements DatabaseEntity {

    public AiProviderConfig {
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalArgumentException("uuid is required");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
    }
}
