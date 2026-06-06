package sh.vork.setup;

import com.jadaptive.orm.DatabaseEntity;

/**
 * Global system settings persisted to MongoDB.
 *
 * <p>A single document is stored with {@code uuid = "global"}.
 * Use {@link SystemSettingsService} to read and write it.
 */
public record SystemSettings(
        String uuid,            // always "global"
        String defaultProvider, // AiProvider enum name, e.g. "GEMINI"
        String defaultModelId   // model ID, e.g. "gemini-2.5-flash"
) implements DatabaseEntity {}
