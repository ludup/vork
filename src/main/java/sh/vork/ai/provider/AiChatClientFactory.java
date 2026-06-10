package sh.vork.ai.provider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import com.google.genai.Client;

import sh.vork.ai.AiProvider;
import sh.vork.ai.config.AiConfig;

/**
 * Builds and caches {@link ChatClient} instances for each AI provider.
 *
 * <p>Gemini uses the Spring Boot auto-configured client from the registry.
 * OpenAI and Ollama clients are constructed programmatically from credentials
 * stored in {@link AiProviderConfig} and cached by a config hash so a new
 * client is created only when the stored credentials change.
 *
 * <p>Invalidate the cache by calling {@link #invalidate(AiProvider)} after
 * saving new credentials.
 */
@Component
public class AiChatClientFactory {

    private static final Logger log = LoggerFactory.getLogger(AiChatClientFactory.class);

    private final AiProviderConfigService configService;

    /** Cache key = provider:configHash, value = built ChatClient. */
    private final Map<String, ChatClient> cache = new ConcurrentHashMap<>();

    public AiChatClientFactory(AiProviderConfigService configService) {
        this.configService = configService;
    }

    /**
     * Returns the base {@link ChatClient} for {@code provider}.
     *
     * <p>For Gemini and the background scheduler the auto-configured client is
     * returned directly.  For OpenAI and Ollama a client is built from the
     * stored {@link AiProviderConfig} and cached; returns {@code null} when
     * the provider is not configured.
     */
    public ChatClient getBaseClient(AiProvider provider) {
        return switch (provider) {
            case GEMINI, BACKGROUND_SCHEDULER -> cachedClient(AiProvider.GEMINI, this::buildGeminiClient);
            case OPENAI  -> cachedClient(provider, this::buildOpenAiClient);
            case OLLAMA  -> cachedClient(provider, this::buildOllamaClient);
            case GROQ    -> cachedClient(provider, this::buildGroqClient);
            default      -> null;
        };
    }

    /** Removes all cached clients for {@code provider}, forcing a rebuild on next access. */
    public void invalidate(AiProvider provider) {
        cache.entrySet().removeIf(e -> e.getKey().startsWith(provider.name() + ":"));
        log.debug("ChatClient cache invalidated [provider={}]", provider);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface ClientBuilder {
        ChatClient build(AiProviderConfig config);
    }

    private ChatClient cachedClient(AiProvider provider, ClientBuilder builder) {
        AiProviderConfig config = configService.getConfig(provider);
        if (config == null || !config.enabled()) {
            return null;
        }
        String cacheKey = provider.name() + ":" + configHash(config);
        return cache.computeIfAbsent(cacheKey, _ -> {
            log.info("Building ChatClient [provider={}]", provider);
            return builder.build(config);
        });
    }

    private ChatClient buildOpenAiClient(AiProviderConfig config) {
        try {
            String apiKey = configService.decryptApiKey(config.apiKey());
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("OpenAI API key is blank — cannot build client");
                return null;
            }
            OpenAiApi api = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .build();
            OpenAiChatOptions opts = OpenAiChatOptions.builder()
                    .model(defaultModel(config, "gpt-4o"))
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(opts)
                    .build();
            return ChatClient.builder(model)
                    .defaultSystem(AiConfig.BASE_SYSTEM_PROMPT)
                    .build();
        } catch (Exception ex) {
            log.error("Failed to build OpenAI ChatClient: {}", ex.getMessage(), ex);
            return null;
        }
    }

    private ChatClient buildGroqClient(AiProviderConfig config) {
        try {
            String apiKey = configService.decryptApiKey(config.apiKey());
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("Groq API key is blank — cannot build client");
                return null;
            }
            OpenAiApi api = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .baseUrl("https://api.groq.com/openai/v1")
                    .build();
            OpenAiChatOptions opts = OpenAiChatOptions.builder()
                    .model(defaultModel(config, "llama-3.3-70b-versatile"))
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(opts)
                    .build();
            return ChatClient.builder(model)
                    .defaultSystem(AiConfig.BASE_SYSTEM_PROMPT)
                    .build();
        } catch (Exception ex) {
            log.error("Failed to build Groq ChatClient: {}", ex.getMessage(), ex);
            return null;
        }
    }

    private ChatClient buildOllamaClient(AiProviderConfig config) {
        try {
            String baseUrl = config.baseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "http://localhost:11434";
            }
            OllamaApi api = OllamaApi.builder()
                    .baseUrl(baseUrl)
                    .build();
            OllamaChatOptions opts = OllamaChatOptions.builder()
                    .model(defaultModel(config, "llama3.2"))
                    .build();
            OllamaChatModel model = OllamaChatModel.builder()
                    .ollamaApi(api)
                    .defaultOptions(opts)
                    .build();
            return ChatClient.builder(model)
                    .defaultSystem(AiConfig.BASE_SYSTEM_PROMPT)
                    .build();
        } catch (Exception ex) {
            log.error("Failed to build Ollama ChatClient: {}", ex.getMessage(), ex);
            return null;
        }
    }

    private ChatClient buildGeminiClient(AiProviderConfig config) {
        try {
            String apiKey = configService.decryptApiKey(config.apiKey());
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("Gemini API key is blank — cannot build client");
                return null;
            }
            Client client = Client.builder().apiKey(apiKey).build();
            GoogleGenAiChatOptions opts = GoogleGenAiChatOptions.builder()
                    .model(defaultModel(config, "gemini-2.5-flash"))
                    .build();
            GoogleGenAiChatModel model = GoogleGenAiChatModel.builder()
                    .genAiClient(client)
                    .defaultOptions(opts)
                    .build();
            return ChatClient.builder(model)
                    .defaultSystem(AiConfig.BASE_SYSTEM_PROMPT)
                    .build();
        } catch (Exception ex) {
            log.error("Failed to build Gemini ChatClient: {}", ex.getMessage(), ex);
            return null;
        }
    }

    private static String defaultModel(AiProviderConfig config, String fallback) {
        return (config.defaultModel() != null && !config.defaultModel().isBlank())
                ? config.defaultModel()
                : fallback;
    }

    private static String configHash(AiProviderConfig config) {
        // Simple hash: combine fields that affect the connection
        int h = 31;
        if (config.apiKey()  != null) h = h * 31 + config.apiKey().hashCode();
        if (config.baseUrl() != null) h = h * 31 + config.baseUrl().hashCode();
        if (config.defaultModel() != null) h = h * 31 + config.defaultModel().hashCode();
        return Integer.toHexString(h);
    }
}
