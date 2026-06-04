package sh.vork.ai.registry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Assigns a display category to an AI tool {@code @Bean} factory method.
 *
 * <p>The value is read by {@link ToolRegistry} at startup and stored in the
 * resulting {@link ToolDescriptor}, which is used by the Tool Inspector UI
 * to group tools into logical sections.  Methods without this annotation
 * default to the category {@code "General"}.
 *
 * <pre>{@code
 * @Bean
 * @ToolCategory("SSH & File Transfer")
 * public ToolCallback connectSsh(SshConnectTool tool) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolCategory {
    String value();
}
