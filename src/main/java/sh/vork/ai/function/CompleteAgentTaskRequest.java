package sh.vork.ai.function;

/**
 * Input schema for the {@code completeAgentTask} tool.
 *
 * <p>No fields are required — invoking this tool signals that the currently
 * active sub-agent has finished its objective and the session should revert
 * to the previous agent persona on the stack.
 */
public record CompleteAgentTaskRequest() {}
