package sh.vork.ai.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jadaptive.orm.DatabaseRepository;

import sh.vork.ai.AiProvider;
import sh.vork.ai.security.encrypt.EncryptionService;

/**
 * CRUD service for {@link AiProviderConfig}.
 *
 * <p>The primary key ({@code uuid}) is always {@code provider.name().toLowerCase()}
 * so configs are looked up by provider without a secondary index.
 *
 * <p>API keys are encrypted at rest via {@link EncryptionService} before being
 * persisted.  Callers that need the plaintext key must call
 * {@link #decryptApiKey(String)}.
 */
@Service
public class AiProviderConfigService {

    private static final Logger log = LoggerFactory.getLogger(AiProviderConfigService.class);

    private final DatabaseRepository<AiProviderConfig> configRepo;
    private final EncryptionService encryptionService;

    public AiProviderConfigService(DatabaseRepository<AiProviderConfig> configRepo,
                                   EncryptionService encryptionService) {
        this.configRepo        = configRepo;
        this.encryptionService = encryptionService;
    }

    /**
     * Decrypts an API key previously encrypted by this service.
     * Returns {@code null} if {@code encryptedKey} is {@code null} or blank.
     */
    public String decryptApiKey(String encryptedKey) {
        if (encryptedKey == null || encryptedKey.isBlank()) {
            return encryptedKey;
        }
        return encryptionService.decrypt(encryptedKey);
    }

    /** Returns the stored config for the given provider, or {@code null} if none is saved. */
    public AiProviderConfig getConfig(AiProvider provider) {
        return configRepo.get(key(provider));
    }

    /**
     * Saves (upserts) the configuration for a provider.
     *
     * @param provider     the provider
     * @param apiKey       API key (may be {@code null} for Ollama)
     * @param baseUrl      base URL (used by Ollama; ignored for cloud providers)
     * @param defaultModel default model ID to pre-select in the UI
     * @param enabled      whether the provider is enabled
     */
    public AiProviderConfig saveConfig(AiProvider provider,
                                       String apiKey,
                                       String baseUrl,
                                       String defaultModel,
                                       boolean enabled) {
        String k = key(provider);
        String storedKey = (apiKey != null && !apiKey.isBlank())
                ? encryptionService.encrypt(apiKey)
                : apiKey;
        AiProviderConfig config = new AiProviderConfig(k, provider.name(), storedKey, baseUrl, enabled, defaultModel);
        configRepo.save(config);
        log.info("AI provider config saved [provider={}]", provider);
        return config;
    }

    /** Deletes the stored config for a provider (resets to unconfigured). */
    public void deleteConfig(AiProvider provider) {
        AiProviderConfig existing = configRepo.get(key(provider));
        if (existing != null) {
            configRepo.delete(key(provider));
            log.info("AI provider config deleted [provider={}]", provider);
        }
    }

    private static String key(AiProvider provider) {
        return provider.name().toLowerCase();
    }
}
