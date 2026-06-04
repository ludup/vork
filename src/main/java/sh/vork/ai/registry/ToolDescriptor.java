package sh.vork.ai.registry;

/**
 * A snapshot of a tool's identity and interface contract.
 *
 * <p>Instances are produced by {@link ToolRegistry} from the Spring AI
 * {@code ToolDefinition} carried by each {@code ToolCallback} bean.
 *
 * @param id              Spring bean name (matches the value used in
 *                        {@link sh.vork.ai.agent.AgentTemplate#allowedTools()})
 * @param name            tool definition name (typically equals {@code id})
 * @param friendlyName    human-readable label derived from the bean name
 *                        (camelCase expanded to Title Case words)
 * @param category        logical grouping assigned via {@link ToolCategory};
 *                        defaults to {@code "General"} when not annotated
 * @param description     functional description surfaced to operators and UIs
 * @param parameterSchema JSON Schema string for the tool's input type
 * @param restricted      {@code true} when the {@code @Bean} factory method is
 *                        annotated with {@link sh.vork.ai.security.Restricted},
 *                        meaning the tool enforces authorization checks at runtime
 */
public record ToolDescriptor(
        String  id,
        String  name,
        String  friendlyName,
        String  category,
        String  description,
        String  parameterSchema,
        boolean restricted
) {}
