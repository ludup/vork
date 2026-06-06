package sh.vork.ai.discovery;

import java.util.List;

/**
 * Strategy for discovering the list of models available from one AI provider.
 *
 * <p>Each implementation is responsible for calling the provider's HTTP API,
 * handling missing credentials gracefully, and returning an empty list rather
 * than throwing when the provider is unreachable or unconfigured.
 */
public interface ModelDiscoveryProvider {

    /** Lowercase provider tag matching {@link DiscoveredModel#provider()} (e.g. {@code "openai"}). */
    String getProviderName();

    /**
     * Calls the provider API and returns all available models.
     * Must never throw — return {@link List#of()} on any failure.
     */
    List<DiscoveredModel> discoverModels();
}
