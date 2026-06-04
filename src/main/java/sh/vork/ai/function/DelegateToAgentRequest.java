package sh.vork.ai.function;

/**
 * Input schema for the {@code delegateToAgent} tool.
 *
 * @param agentTemplateUuid UUID of the {@link sh.vork.ai.agent.AgentTemplate}
 *                          to activate on the current session's stack
 */
public record DelegateToAgentRequest(String agentTemplateUuid) {}
