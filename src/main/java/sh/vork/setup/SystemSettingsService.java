package sh.vork.setup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jadaptive.orm.DatabaseRepository;

/**
 * CRUD service for the single global {@link SystemSettings} document.
 *
 * <p>The document is stored with the fixed primary key {@code "global"}.
 */
@Service
public class SystemSettingsService {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingsService.class);
    private static final String GLOBAL_KEY = "global";

    private final DatabaseRepository<SystemSettings> repo;

    public SystemSettingsService(DatabaseRepository<SystemSettings> repo) {
        this.repo = repo;
    }

    /** Returns the global settings, or {@code null} if none have been saved yet. */
    public SystemSettings getGlobal() {
        return repo.get(GLOBAL_KEY);
    }

    /**
     * Persists the global default provider and model, preserving any existing {@code appBaseUrl}
     * and {@code defaultOobTimeoutMinutes}.
     */
    public SystemSettings setGlobal(String provider, String modelId) {
        SystemSettings existing = getGlobal();
        String baseUrl = existing != null ? existing.appBaseUrl() : null;
        int oobTimeout = existing != null ? existing.defaultOobTimeoutMinutes() : 0;
        return setGlobal(provider, modelId, baseUrl, oobTimeout);
    }

    /**
     * Persists the global default provider, model, and public base URL, preserving
     * any existing {@code defaultOobTimeoutMinutes}.
     */
    public SystemSettings setGlobal(String provider, String modelId, String appBaseUrl) {
        SystemSettings existing = getGlobal();
        int oobTimeout = existing != null ? existing.defaultOobTimeoutMinutes() : 0;
        return setGlobal(provider, modelId, appBaseUrl, oobTimeout);
    }

    /**
     * Persists all global settings including the default OOB relay timeout.
     *
     * @param provider              AiProvider enum name
     * @param modelId               model identifier
     * @param appBaseUrl            public-facing base URL; may be null
     * @param defaultOobTimeoutMins default OOB timeout in minutes; 0 means 15 minutes will be used at runtime
     */
    public SystemSettings setGlobal(String provider, String modelId, String appBaseUrl,
                                     int defaultOobTimeoutMins) {
        String effectiveUrl = (appBaseUrl != null && !appBaseUrl.isBlank()) ? appBaseUrl
                : (getGlobal() != null ? getGlobal().appBaseUrl() : null);
        SystemSettings settings = new SystemSettings(GLOBAL_KEY, provider, modelId,
                effectiveUrl, defaultOobTimeoutMins);
        repo.save(settings);
        log.info("Global settings updated [provider={}, model={}, baseUrl={}, oobTimeoutMins={}]",
                provider, modelId, appBaseUrl, defaultOobTimeoutMins);
        return settings;
    }

    /**
     * Returns the effective default OOB timeout: the stored value if &gt; 0,
     * otherwise 15 minutes (suitable for Telegram / interactive sessions).
     */
    public int getDefaultOobTimeoutMinutes() {
        SystemSettings s = getGlobal();
        return (s != null && s.defaultOobTimeoutMinutes() > 0) ? s.defaultOobTimeoutMinutes() : 15;
    }
}
