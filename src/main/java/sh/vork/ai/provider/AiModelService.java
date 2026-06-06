package sh.vork.ai.provider;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import sh.vork.ai.AiProvider;
import sh.vork.ai.discovery.ModelDiscoveryOrchestrator;

/**
 * Returns the catalogue of AI providers and their available models.
 *
 * <p>Model lists are hard-coded; a provider is reported as "configured"
 * when its API credentials are stored in {@link AiProviderConfig}.
 * Gemini is always considered configured because its key lives in
 * {@code application.yml}.
 */
@Service
public class AiModelService {

    // ── Default model fallbacks (used when no preference is stored) ───────────

    /** Default model for each provider, used when a session has no model set. */
    public static final Map<AiProvider, String> DEFAULT_MODELS = Map.of(
            AiProvider.GEMINI,               "gemini-2.5-flash",
            AiProvider.OPENAI,               "gpt-4o",
            AiProvider.OLLAMA,               "llama3.2",
            AiProvider.BACKGROUND_SCHEDULER, "gemini-2.5-flash"
    );

    // ─────────────────────────────────────────────────────────────────────────

    private final AiProviderConfigService   configService;
    private final ModelDiscoveryOrchestrator orchestrator;

    public AiModelService(AiProviderConfigService configService,
                          ModelDiscoveryOrchestrator orchestrator) {
        this.configService  = configService;
        this.orchestrator   = orchestrator;
    }

    /**
     * Returns all provider/model groups suitable for the chat model dropdown.
     * Only providers that are configured are returned.
     */
    public List<ProviderModelGroup> getConfiguredProviders() {
        return getAllProviders().stream()
                .filter(ProviderModelGroup::configured)
                .toList();
    }

    /**
     * Returns all provider/model groups (configured and unconfigured).
     * Used by the settings page.
     */
    public List<ProviderModelGroup> getAllProviders() {
        return List.of(
                buildGroup(AiProvider.GEMINI,  "Gemini"),
                buildGroup(AiProvider.OPENAI,  "ChatGPT"),
                buildGroup(AiProvider.OLLAMA,  "Ollama")
        );
    }

    /** Returns the default model ID for a provider. */
    public String defaultModelFor(AiProvider provider) {
        AiProviderConfig cfg = configService.getConfig(provider);
        if (cfg != null && cfg.defaultModel() != null && !cfg.defaultModel().isBlank()) {
            return cfg.defaultModel();
        }
        return DEFAULT_MODELS.getOrDefault(provider, "");
    }

    /** Returns the stored config for a provider (may be {@code null}). */
    public AiProviderConfig getConfig(AiProvider provider) {
        return configService.getConfig(provider);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private ProviderModelGroup buildGroup(AiProvider provider, String label) {
        boolean configured = isConfigured(provider);
        String defaultModel = defaultModelFor(provider);
        List<ModelEntry> models = orchestrator.discoverForProvider(provider.name().toLowerCase())
                .stream()
                .map(dm -> new ModelEntry(dm.id(), dm.displayName(), dm.id().equals(defaultModel)))
                .toList();
        return new ProviderModelGroup(provider.name(), label, configured, defaultModel, models);
    }

    private boolean isConfigured(AiProvider provider) {
        return switch (provider) {
            // Gemini is always available via application.yml
            case GEMINI, BACKGROUND_SCHEDULER -> true;
            default -> {
                AiProviderConfig cfg = configService.getConfig(provider);
                yield cfg != null && cfg.enabled() && hasCredentials(provider, cfg);
            }
        };
    }

    private static boolean hasCredentials(AiProvider provider, AiProviderConfig cfg) {
        return switch (provider) {
            case OLLAMA -> cfg.baseUrl() != null && !cfg.baseUrl().isBlank();
            default    -> cfg.apiKey()  != null && !cfg.apiKey().isBlank();
        };
    }

    // ── Nested DTOs ──────────────────────────────────────────────────────────

    /** A provider and its available models. */
    public record ProviderModelGroup(
            String providerKey,
            String providerLabel,
            boolean configured,
            String defaultModel,
            List<ModelEntry> models
    ) {}

    /** A single model entry in the catalogue. */
    public record ModelEntry(
            String modelId,
            String label,
            boolean isDefault
    ) {}
}
