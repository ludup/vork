package sh.vork.ai.agent;

import com.jadaptive.orm.DatabaseEntity;

import java.util.List;

/**
 * A named AI agent configuration stored in MongoDB.
 *
 * <p>An {@code AgentTemplate} defines the persona and capability boundary for an
 * agent that can be activated within an {@link sh.vork.ai.entity.AiSession}.
 * When a session has an active agent template the orchestration layer injects the
 * template's {@code systemPrompt} into every request and restricts available tools
 * to the {@code allowedTools} list.
 *
 * @param uuid         unique document ID (MongoDB {@code _id})
 * @param name         human-friendly label shown in management UIs
 * @param systemPrompt directives prepended to the system prompt for this agent
 * @param allowedTools Spring bean IDs of the {@code ToolCallback} beans this
 *                     agent may invoke; an empty list means no tool restriction
 *                     is applied (all tools available)
 * @param systemAgent  {@code true} for built-in agents that must not be deleted
 */
public record AgentTemplate(
        String       uuid,
        String       name,
        String       systemPrompt,
        List<String> allowedTools,
        boolean      systemAgent
) implements DatabaseEntity {

    public AgentTemplate {
        if (name == null || name.isBlank()) {
            name = "Unnamed Agent";
        }
        if (systemPrompt == null) {
            systemPrompt = "";
        }
        if (allowedTools == null) {
            allowedTools = List.of();
        }
    }
}
