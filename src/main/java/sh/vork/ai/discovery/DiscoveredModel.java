package sh.vork.ai.discovery;

/**
 * A model discovered at runtime from a provider's live API.
 *
 * @param id          Provider-native model identifier (e.g. {@code "gpt-4o"}, {@code "gemini-2.5-flash"})
 * @param displayName Human-readable name suitable for the UI
 * @param provider    Lowercase provider tag — one of {@code "openai"}, {@code "gemini"}, {@code "ollama"}
 * @param isLocal     {@code true} for models running on locally hosted infrastructure (Ollama)
 */
public record DiscoveredModel(
        String id,
        String displayName,
        String provider,
        boolean isLocal
) {}
