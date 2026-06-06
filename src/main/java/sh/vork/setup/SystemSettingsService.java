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
     * Persists the global default provider and model.
     *
     * @param provider   AiProvider enum name (e.g. {@code "GEMINI"})
     * @param modelId    model identifier (e.g. {@code "gemini-2.5-flash"})
     */
    public SystemSettings setGlobal(String provider, String modelId) {
        SystemSettings settings = new SystemSettings(GLOBAL_KEY, provider, modelId);
        repo.save(settings);
        log.info("Global default updated [provider={}, model={}]", provider, modelId);
        return settings;
    }
}
