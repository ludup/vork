package sh.vork.notification;

/**
 * Describes a single configuration field exposed by a {@link NotificationProvider}.
 *
 * @param key         machine key used in the settings map (e.g. {@code "apiKey"})
 * @param label       human-readable label shown in the UI (e.g. {@code "API Key"})
 * @param type        HTML input type: {@code "text"}, {@code "password"}, or {@code "email"}
 * @param required    whether the field must be non-blank before saving
 * @param placeholder hint text shown in the empty input
 */
public record SettingDefinition(
        String  key,
        String  label,
        String  type,
        boolean required,
        String  placeholder
) {
    public static SettingDefinition required(String key, String label, String type, String placeholder) {
        return new SettingDefinition(key, label, type, true, placeholder);
    }

    public static SettingDefinition optional(String key, String label, String type, String placeholder) {
        return new SettingDefinition(key, label, type, false, placeholder);
    }
}
