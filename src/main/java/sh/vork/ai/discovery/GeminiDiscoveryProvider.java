package sh.vork.ai.discovery;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import sh.vork.ai.AiProvider;
import sh.vork.ai.provider.AiProviderConfig;
import sh.vork.ai.provider.AiProviderConfigService;

/**
 * Discovers available Gemini models via
 * {@code GET https://generativelanguage.googleapis.com/v1beta/models}.
 *
 * <p>The API key is taken from {@code spring.ai.google.genai.api-key} in
 * {@code application.yml}.  Discovery returns an empty list when the key is
 * absent or the API call fails.
 */
@Component
public class GeminiDiscoveryProvider implements ModelDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiDiscoveryProvider.class);
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private final RestClient restClient;
    private final AiProviderConfigService configService;

    public GeminiDiscoveryProvider(RestClient.Builder restClientBuilder,
                                   AiProviderConfigService configService) {
        this.restClient    = restClientBuilder.baseUrl(BASE_URL).build();
        this.configService = configService;
    }

    /**
     * Matches the canonical stable Gemini model tiers across any version number.
     * Accepted examples: gemini-pro, gemini-flash, gemini-1.5-pro, gemini-2.5-flash,
     * gemini-flash-lite, gemini-2.0-flash-lite, gemini-pro-latest, gemini-2.5-flash-latest.
     * Rejected: anything with "preview", experimental suffixes, or unknown tier names.
     */
    private static final Pattern STABLE_GEMINI_PATTERN =
            Pattern.compile("^gemini-(?:[0-9.]+-)?(?:pro|flash|flash-lite)(?:-latest)?$");

    @Override
    public String getProviderName() {
        return "gemini";
    }

    // ── Static filter helpers ────────────────────────────────────────────────

    private static String rawModelName(Map<String, Object> m) {
        return (String) m.get("name");
    }

    private static boolean supportsGenerateContent(Map<String, Object> m) {
        Object methods = m.get("supportedGenerationMethods");
        if (!(methods instanceof List<?> list)) return false;
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch("generateContent"::equals);
    }

    /**
     * Returns {@code true} when the short model ID (without the {@code models/} prefix)
     * matches {@link #STABLE_GEMINI_PATTERN} and does not contain the word {@code "preview"}.
     */
    private static boolean isStableGeminiModel(String rawName) {
        if (rawName == null) return false;
        String id = rawName.startsWith("models/") ? rawName.substring("models/".length()) : rawName;
        if (id.toLowerCase().contains("preview")) return false;
        return STABLE_GEMINI_PATTERN.matcher(id).matches();
    }

    /**
     * Formats a model ID into a human-readable display name when the API does not
     * supply one.  Hyphens become spaces; each non-numeric word is title-cased.
     * Example: {@code "gemini-2.5-flash"} → {@code "Gemini 2.5 Flash"}.
     */
    private static String formatDisplayName(String id) {
        if (id == null || id.isBlank()) return id;
        String[] parts = id.split("-");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            String word = parts[i];
            if (!word.isEmpty() && !Character.isDigit(word.charAt(0))) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            } else {
                sb.append(word);
            }
        }
        return sb.toString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DiscoveredModel> discoverModels() {
        AiProviderConfig cfg = configService.getConfig(AiProvider.GEMINI);
        if (cfg == null || cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            log.debug("Gemini: no API key configured, skipping discovery");
            return List.of();
        }
        String apiKey = configService.decryptApiKey(cfg.apiKey());
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Gemini: API key could not be decrypted, skipping discovery");
            return List.of();
        }
        log.debug("Gemini: discovering models");
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/models?key={key}", apiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return List.of();
            Object rawModels = response.get("models");
            if (!(rawModels instanceof List<?> list)) return List.of();
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(o -> (Map<String, Object>) o)
                    // Only keep models that support generateContent (excludes embedding, etc.)
                    .filter(GeminiDiscoveryProvider::supportsGenerateContent)
                    // Keep only stable Pro/Flash/Flash-Lite tiers; reject preview, experimental, etc.
                    .filter(m -> isStableGeminiModel(rawModelName(m)))
                    .map(m -> {
                        String name = rawModelName(m);
                        // "models/gemini-2.5-flash" → "gemini-2.5-flash"
                        String id = (name != null && name.startsWith("models/"))
                                ? name.substring("models/".length()) : name;
                        // Prefer the API-supplied display name; fall back to our formatter.
                        String apiDisplayName = (String) m.get("displayName");
                        String displayName = (apiDisplayName != null && !apiDisplayName.isBlank())
                                ? apiDisplayName : formatDisplayName(id);
                        return new DiscoveredModel(id, displayName, "gemini", false);
                    })
                    .filter(m -> m.id() != null && !m.id().isBlank())
                    .sorted((a, b) -> a.id().compareToIgnoreCase(b.id()))
                    .toList();
        } catch (Exception e) {
            log.warn("Gemini model discovery failed: {}", e.getMessage());
            return List.of();
        }
    }
}
