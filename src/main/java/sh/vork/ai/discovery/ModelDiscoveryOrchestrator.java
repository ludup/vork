package sh.vork.ai.discovery;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Aggregates model discovery across all registered {@link ModelDiscoveryProvider} strategies.
 *
 * <p>Results are cached per-provider for 24 hours to avoid hammering provider APIs on every
 * page load.  Call {@link #invalidate(String)} after saving new credentials so the next
 * request fetches a fresh list.
 */
@Component
public class ModelDiscoveryOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ModelDiscoveryOrchestrator.class);
    private static final long CACHE_TTL_SECONDS = 24L * 60 * 60;

    private final Collection<ModelDiscoveryProvider> providers;

    private record CacheEntry(List<DiscoveredModel> models, Instant fetchedAt) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public ModelDiscoveryOrchestrator(Collection<ModelDiscoveryProvider> providers) {
        this.providers = providers;
    }

    /**
     * Returns all models discovered across every registered provider.
     * Results are flattened into a single list.
     */
    public List<DiscoveredModel> getAllDiscoveredModels() {
        return providers.stream()
                .flatMap(p -> getFromCacheOrFetch(p).stream())
                .toList();
    }

    /**
     * Returns discovered models for one provider.
     *
     * @param providerName lowercase provider tag (e.g. {@code "openai"})
     */
    public List<DiscoveredModel> discoverForProvider(String providerName) {
        return providers.stream()
                .filter(p -> p.getProviderName().equalsIgnoreCase(providerName))
                .findFirst()
                .map(this::getFromCacheOrFetch)
                .orElse(List.of());
    }

    /**
     * Evicts the cached model list for a provider so the next call triggers a fresh fetch.
     * Call this after saving or deleting provider credentials.
     */
    public void invalidate(String providerName) {
        cache.remove(providerName.toLowerCase());
        log.debug("Model discovery cache invalidated for provider: {}", providerName);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private List<DiscoveredModel> getFromCacheOrFetch(ModelDiscoveryProvider provider) {
        String key = provider.getProviderName();
        CacheEntry entry = cache.get(key);
        if (entry != null && Instant.now().isBefore(entry.fetchedAt().plusSeconds(CACHE_TTL_SECONDS))) {
            log.debug("Model discovery cache hit for provider: {}", key);
            return entry.models();
        }
        log.debug("Fetching models from provider: {}", key);
        List<DiscoveredModel> models = provider.discoverModels();
        cache.put(key, new CacheEntry(models, Instant.now()));
        log.debug("Discovered {} model(s) for provider: {}", models.size(), key);
        return models;
    }
}
