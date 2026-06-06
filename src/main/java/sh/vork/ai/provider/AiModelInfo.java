package sh.vork.ai.provider;

/**
 * Describes a single AI model that can be selected in the chat UI.
 *
 * @param providerKey   Enum name of the owning {@link sh.vork.ai.AiProvider} (e.g. {@code "GEMINI"})
 * @param providerLabel Human-readable provider name shown in the UI (e.g. {@code "Gemini"})
 * @param modelId       Model identifier sent to the AI API (e.g. {@code "gemini-2.5-flash"})
 * @param modelLabel    Human-readable model name shown in the UI (e.g. {@code "2.5 Flash"})
 * @param isDefault     {@code true} when this is the recommended default for the provider
 * @param configured    {@code true} when the provider has API credentials configured and is usable
 */
public record AiModelInfo(
        String providerKey,
        String providerLabel,
        String modelId,
        String modelLabel,
        boolean isDefault,
        boolean configured
) {}
