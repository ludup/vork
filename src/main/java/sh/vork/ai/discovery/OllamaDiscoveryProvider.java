package sh.vork.ai.discovery;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import sh.vork.ai.AiProvider;
import sh.vork.ai.provider.AiProviderConfig;
import sh.vork.ai.provider.AiProviderConfigService;

/**
 * Discovers models from a locally running Ollama instance via {@code GET /api/tags}.
 *
 * <p>The base URL is read from the persisted {@link AiProviderConfig} (defaulting to
 * {@code http://localhost:11434} when no config is stored).  Returns an empty list
 * silently when Ollama is not reachable.
 */
@Component
public class OllamaDiscoveryProvider implements ModelDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaDiscoveryProvider.class);
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    private final AiProviderConfigService configService;

    public OllamaDiscoveryProvider(AiProviderConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String getProviderName() {
        return "ollama";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DiscoveredModel> discoverModels() {
        AiProviderConfig cfg = configService.getConfig(AiProvider.OLLAMA);
        String baseUrl = (cfg != null && cfg.baseUrl() != null && !cfg.baseUrl().isBlank())
                ? cfg.baseUrl() : DEFAULT_BASE_URL;
        log.debug("Ollama: discovering models at {}", baseUrl);
        try {
            RestClient restClient = RestClient.builder().baseUrl(baseUrl).build();
            Map<String, Object> response = restClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return List.of();
            Object rawModels = response.get("models");
            if (!(rawModels instanceof List<?> list)) return List.of();
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(o -> (Map<String, Object>) o)
                    .map(m -> {
                        String name = (String) m.get("name");
                        return new DiscoveredModel(name, name, "ollama", true);
                    })
                    .filter(m -> m.id() != null && !m.id().isBlank())
                    .toList();
        } catch (ResourceAccessException e) {
            log.debug("Ollama not reachable at {}: {}", baseUrl, e.getMessage());
            return List.of();
        } catch (Exception e) {
            log.warn("Ollama model discovery failed: {}", e.getMessage());
            return List.of();
        }
    }
}
