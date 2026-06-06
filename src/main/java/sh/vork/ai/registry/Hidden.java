package sh.vork.ai.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring {@code @Bean} factory method that produces a
 * {@link org.springframework.ai.tool.ToolCallback} as a hidden infrastructure tool.
 *
 * <p>Hidden tools are excluded from the public tool registry (they will not appear in
 * {@link ToolRegistry#getAvailableTools()}, the management API, or the agent tool-picker UI).
 * They are also excluded from the global {@code securedToolCallbackMap} so they cannot
 * accidentally be exposed to AI agents by default.
 *
 * <p>Hidden tools are intended to be injected into specific sessions programmatically
 * via {@link sh.vork.ai.session.SessionToolStore}, making them visible only to the AI
 * agent running inside that session.
 *
 * <p>Usage:
 * <pre>{@code
 * @Bean
 * @Hidden
 * public ToolCallback completeBackgroundTask(...) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Hidden {
}
