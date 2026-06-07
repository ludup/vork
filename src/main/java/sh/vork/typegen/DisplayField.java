package sh.vork.typegen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Java record component for rendering in the Data Inspector UI.
 *
 * <p>Apply this annotation to record components to control how they are displayed
 * in the Data Inspector table and create/edit forms. Types without any
 * {@code @DisplayField} annotations fall back to showing all top-level
 * {@code String}, primitive, and numeric fields automatically.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * package sh.vork.generated;
 *
 * import sh.vork.typegen.DisplayField;
 * import com.jadaptive.orm.DatabaseEntity;
 *
 * public record Customer(
 *     @DisplayField(label = "ID", order = 0, tableColumn = true, inputType = "text")
 *     String uuid,
 *
 *     @DisplayField(label = "Full Name", order = 1, tableColumn = true, inputType = "text", required = true)
 *     String name,
 *
 *     @DisplayField(label = "Email", order = 2, tableColumn = true, inputType = "email")
 *     String email,
 *
 *     @DisplayField(label = "Address", order = 3, tableColumn = false)
 *     Address address
 * ) implements DatabaseEntity {}
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface DisplayField {

    /**
     * Human-readable label for the field. Defaults to the field name (camelCase
     * converted to "Title Case" by the UI if blank).
     */
    String label() default "";

    /**
     * Sort order for columns in the Data Inspector table and form sections.
     * Lower values appear first. Defaults to {@link Integer#MAX_VALUE} (append after
     * any explicitly ordered fields).
     */
    int order() default Integer.MAX_VALUE;

    /**
     * Whether this field is shown as a column in the Data Inspector table.
     * Set to {@code false} for complex nested records or large text fields that
     * are better viewed only in the create/edit form.
     * <p>Defaults to {@code true}.
     */
    boolean tableColumn() default true;

    /**
     * Input type hint for the form renderer. Valid values:
     * <ul>
     *   <li>{@code "auto"} — inferred from the Java type (default)</li>
     *   <li>{@code "text"} — single-line text input</li>
     *   <li>{@code "textarea"} — multi-line text area</li>
     *   <li>{@code "number"} — numeric input</li>
     *   <li>{@code "email"} — e-mail address input</li>
     *   <li>{@code "password"} — masked password input</li>
     *   <li>{@code "date"} — ISO date picker</li>
     *   <li>{@code "datetime-local"} — date + time picker</li>
     *   <li>{@code "checkbox"} — boolean toggle</li>
     *   <li>{@code "url"} — URL input</li>
     * </ul>
     */
    String inputType() default "auto";

    /**
     * Optional placeholder text shown inside the form input when empty.
     */
    String placeholder() default "";

    /**
     * Whether the field is required in the create/edit form.
     * The UI will prevent submission if a required field is blank.
     */
    boolean required() default false;
}
