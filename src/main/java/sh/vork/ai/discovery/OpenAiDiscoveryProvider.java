package sh.vork.ai.discovery;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import sh.vork.ai.AiProvider;
import sh.vork.ai.provider.AiProviderConfig;
import sh.vork.ai.provider.AiProviderConfigService;

/**
 * Discovers available OpenAI models via {@code GET https://api.openai.com/v1/models}.
 *
 * <p>The API key is read from the persisted {@link AiProviderConfig} stored in the
 * database by the user on the settings page.  Discovery returns an empty list when
 * no key has been configured yet or when the API call fails.
 */
@Component
public class OpenAiDiscoveryProvider implements ModelDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiDiscoveryProvider.class);
    private static final String BASE_URL = "https://api.openai.com/v1";

    private final RestClient restClient;
    private final AiProviderConfigService configService;

    public OpenAiDiscoveryProvider(RestClient.Builder restClientBuilder,
                                   AiProviderConfigService configService) {
        this.restClient    = restClientBuilder.baseUrl(BASE_URL).build();
        this.configService = configService;
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DiscoveredModel> discoverModels() {
        AiProviderConfig cfg = configService.getConfig(AiProvider.OPENAI);
        if (cfg == null || cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            log.debug("OpenAI: no API key configured, skipping discovery");
            return List.of();
        }
        String apiKey = configService.decryptApiKey(cfg.apiKey());
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("OpenAI: API key could not be decrypted, skipping discovery");
            return List.of();
        }
        log.debug("OpenAI: discovering models");
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/models")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return List.of();
            Object rawData = response.get("data");
            if (!(rawData instanceof List<?> list)) return List.of();
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(o -> (Map<String, Object>) o)
                    .map(m -> {
                        String id = (String) m.get("id");
                        return new DiscoveredModel(id, id, "openai", false);
                    })
                    .filter(m -> m.id() != null && !m.id().isBlank())
                    .sorted((a, b) -> a.id().compareToIgnoreCase(b.id()))
                    .toList();
        } catch (Exception e) {
            log.warn("OpenAI model discovery failed: {}", e.getMessage());
            return List.of();
        }
    }
}
