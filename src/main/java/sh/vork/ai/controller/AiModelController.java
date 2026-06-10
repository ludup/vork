package sh.vork.ai.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import sh.vork.ai.AiProvider;
import sh.vork.ai.provider.AiChatClientFactory;
import sh.vork.ai.provider.AiModelService;
import sh.vork.ai.provider.AiProviderConfig;
import sh.vork.ai.provider.AiProviderConfigService;
import sh.vork.ai.discovery.ModelDiscoveryOrchestrator;

/**
 * REST API for managing AI provider configurations.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/ai/providers}          — list all providers with config status</li>
 *   <li>{@code GET  /api/ai/providers/{provider}/config} — get config (no sensitive values)</li>
 *   <li>{@code PUT  /api/ai/providers/{provider}/config} — save/update config</li>
 *   <li>{@code DELETE /api/ai/providers/{provider}/config} — remove config (reset to unconfigured)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/providers")
public class AiModelController {

    private static final Logger log = LoggerFactory.getLogger(AiModelController.class);

    private final AiModelService            modelService;
    private final AiProviderConfigService   configService;
    private final AiChatClientFactory       clientFactory;
    private final ModelDiscoveryOrchestrator discoveryOrchestrator;

    public AiModelController(AiModelService modelService,
                              AiProviderConfigService configService,
                              AiChatClientFactory clientFactory,
                              ModelDiscoveryOrchestrator discoveryOrchestrator) {
        this.modelService          = modelService;
        this.configService         = configService;
        this.clientFactory         = clientFactory;
        this.discoveryOrchestrator = discoveryOrchestrator;
    }

    @GetMapping
    public List<AiModelService.ProviderModelGroup> listProviders() {
        log.debug("ENTER listProviders");
        return modelService.getAllProviders();
    }

    @GetMapping("/{provider}/config")
    public ResponseEntity<?> getConfig(@PathVariable String provider) {
        log.debug("ENTER getConfig: [provider={}]", provider);
        AiProvider aiProvider = resolveProvider(provider);
        if (aiProvider == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Unknown provider: " + provider));
        }
        AiProviderConfig config = configService.getConfig(aiProvider);
        if (config == null) {
            return ResponseEntity.ok(Map.of(
                    "provider", aiProvider.name(),
                    "configured", false));
        }
        // Return config without exposing the full API key — just a masked hint
        String maskedKey = maskApiKey(config.apiKey());
        return ResponseEntity.ok(Map.of(
                "provider", aiProvider.name(),
                "configured", true,
                "apiKeyHint", maskedKey != null ? maskedKey : "",
                "baseUrl", config.baseUrl() != null ? config.baseUrl() : "",
                "defaultModel", config.defaultModel() != null ? config.defaultModel() : "",
                "enabled", config.enabled()));
    }

    @PutMapping("/{provider}/config")
    public ResponseEntity<?> saveConfig(@PathVariable String provider,
                                        @RequestBody ProviderConfigRequest request) {
        log.debug("ENTER saveConfig: [provider={}]", provider);
        AiProvider aiProvider = resolveProvider(provider);
        if (aiProvider == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Unknown provider: " + provider));
        }
        if (aiProvider == AiProvider.BACKGROUND_SCHEDULER) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Cannot configure internal provider"));
        }

        // If apiKey is blank/null and provider already has a config, preserve existing key
        String apiKey = request.apiKey();
        if ((apiKey == null || apiKey.isBlank()) && aiProvider != AiProvider.OLLAMA) {
            AiProviderConfig existing = configService.getConfig(aiProvider);
            if (existing != null) {
                apiKey = existing.apiKey();
            }
        }

        configService.saveConfig(aiProvider, apiKey, request.baseUrl(), request.defaultModel(),
                request.enabled() == null || request.enabled());
        clientFactory.invalidate(aiProvider);
        discoveryOrchestrator.invalidate(aiProvider.name().toLowerCase());

        log.info("Provider config updated [provider={}]", aiProvider);
        return ResponseEntity.ok(Map.of("status", "OK", "provider", aiProvider.name()));
    }

    @DeleteMapping("/{provider}/config")
    public ResponseEntity<?> deleteConfig(@PathVariable String provider) {
        log.debug("ENTER deleteConfig: [provider={}]", provider);
        AiProvider aiProvider = resolveProvider(provider);
        if (aiProvider == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Unknown provider: " + provider));
        }
        if (aiProvider == AiProvider.BACKGROUND_SCHEDULER) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", "Cannot remove config for internal provider"));
        }
        configService.deleteConfig(aiProvider);
        clientFactory.invalidate(aiProvider);
        discoveryOrchestrator.invalidate(aiProvider.name().toLowerCase());
        log.info("Provider config removed [provider={}]", aiProvider);
        return ResponseEntity.ok(Map.of("status", "OK"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AiProvider resolveProvider(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            AiProvider p = AiProvider.valueOf(name.toUpperCase());
            // Exclude internal-only entry from external API
            if (p == AiProvider.ANTHROPIC) return null;
            return p;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Returns a masked hint like {@code sk-...abc1} — never the full key. */
    private static String maskApiKey(String key) {
        if (key == null || key.length() < 8) return key == null ? null : "****";
        return key.substring(0, 3) + "..." + key.substring(key.length() - 4);
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record ProviderConfigRequest(String apiKey, String baseUrl, String defaultModel, Boolean enabled) {}
}
