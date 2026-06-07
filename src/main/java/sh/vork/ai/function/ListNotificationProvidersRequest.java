package sh.vork.ai.function;

/**
 * Input schema for the {@code listNotificationProviders} tool.
 *
 * <p>No parameters are needed — the tool returns all configured providers that
 * support direct (unregistered) addressing.
 */
public record ListNotificationProvidersRequest() {}
