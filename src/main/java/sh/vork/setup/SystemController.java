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
    public Map<String, String> getSettings() {
        log.debug("ENTER getSettings");
        SystemSettings s = systemSettingsService.getGlobal();
        if (s == null || s.defaultProvider() == null) {
            log.debug("EXIT getSettings: no global default configured");
            return Map.of();
        }
        log.debug("EXIT getSettings: [provider={}, model={}]", s.defaultProvider(), s.defaultModelId());
        return Map.of(
                "defaultProvider", s.defaultProvider(),
                "defaultModelId",  s.defaultModelId()
        );
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
        systemSettingsService.setGlobal(req.defaultProvider(), req.defaultModelId());
        log.info("Global default updated via settings page [provider={}, model={}]",
                req.defaultProvider(), req.defaultModelId());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    record SettingsRequest(String defaultProvider, String defaultModelId) {}
}
