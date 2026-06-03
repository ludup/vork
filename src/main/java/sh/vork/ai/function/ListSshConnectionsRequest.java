package sh.vork.ai.function;

/**
 * Input schema for the {@code listSshConnections} tool.
 * No parameters are required — the active session is resolved from the MDC.
 */
public record ListSshConnectionsRequest() {}
