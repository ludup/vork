package sh.vork.setup;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes global system settings to authenticated clients.
 *
 * <p>Currently returns the global default AI provider and model so that
 * the chat front-end can pre-select the correct option in the model dropdown.
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final Logger log = LoggerFactory.getLogger(SystemController.class);

    private final SystemSettingsService systemSettingsService;

    public SystemController(SystemSettingsService systemSettingsService) {
        this.systemSettingsService = systemSettingsService;
    }

    /** Returns the global default provider and model, or an empty object if not set. */
    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        log.debug("ENTER getSettings");
        SystemSettings s = systemSettingsService.getGlobal();
        if (s == null || s.defaultProvider() == null) {
            log.debug("EXIT getSettings: no global default configured");
            return Map.of();
        }
        log.debug("EXIT getSettings: [provider={}, model={}]", s.defaultProvider(), s.defaultModelId());
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("defaultProvider",         s.defaultProvider());
        result.put("defaultModelId",          s.defaultModelId());
        result.put("appBaseUrl",              s.appBaseUrl() != null ? s.appBaseUrl() : "");
        result.put("defaultOobTimeoutMinutes", s.defaultOobTimeoutMinutes() > 0 ? s.defaultOobTimeoutMinutes() : 15);
        return result;
    }

    /** Updates the global default provider and model. */
    @PutMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody SettingsRequest req) {
        log.debug("ENTER saveSettings: [provider={}, model={}]", req.defaultProvider(), req.defaultModelId());
        if (req.defaultProvider() == null || req.defaultProvider().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Provider is required."));
        }
        if (req.defaultModelId() == null || req.defaultModelId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Model is required."));
        }
        systemSettingsService.setGlobal(req.defaultProvider(), req.defaultModelId(),
                req.appBaseUrl(), req.defaultOobTimeoutMinutes() > 0 ? req.defaultOobTimeoutMinutes() : 0);
        log.info("Global default updated via settings page [provider={}, model={}, baseUrl={}, oobTimeoutMins={}]",
                req.defaultProvider(), req.defaultModelId(), req.appBaseUrl(), req.defaultOobTimeoutMinutes());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    record SettingsRequest(String defaultProvider, String defaultModelId, String appBaseUrl, int defaultOobTimeoutMinutes) {}
}
