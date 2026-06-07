package sh.vork.ai.security;

/**
 * Optional capability for tools that can provide pretty authorization details
 * for UI rendering.
 */
public interface VisualizableTool {

    /**
     * Extracts a plain-text summary of the tool arguments for display in authorization prompts.
     * Consumers are responsible for applying any formatting (markdown fences, MarkdownV2, etc.)
     * appropriate to their rendering context.
     */
    String formatAuthorizationDetails(String argumentsJson);
}
