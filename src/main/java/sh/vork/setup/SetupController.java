package sh.vork.setup;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import sh.vork.ai.AiProvider;
import sh.vork.ai.discovery.DiscoveredModel;
import sh.vork.ai.discovery.ModelDiscoveryOrchestrator;
import sh.vork.ai.provider.AiChatClientFactory;
import sh.vork.ai.provider.AiProviderConfigService;

/**
 * Serves the first-time setup wizard and the AJAX endpoints it calls.
 *
 * <p>Page endpoints ({@code /setup}) are accessible without authentication.
 * API endpoints ({@code /api/setup/**}) are also unauthenticated so the wizard
 * can create the first admin account.  Both are exempted from CSRF checks in
 * {@code SecurityConfig} because no session/cookie exists during setup.
 */
@Controller
public class SetupController {

    private static final Logger log = LoggerFactory.getLogger(SetupController.class);

    private final SetupService              setupService;
    private final AiProviderConfigService   configService;
    private final AiChatClientFactory       clientFactory;
    private final ModelDiscoveryOrchestrator orchestrator;
    private final SystemSettingsService     systemSettingsService;
    private final AuthenticationManager     authenticationManager;

    public SetupController(SetupService setupService,
                           AiProviderConfigService configService,
                           AiChatClientFactory clientFactory,
                           ModelDiscoveryOrchestrator orchestrator,
                           SystemSettingsService systemSettingsService,
                           AuthenticationManager authenticationManager) {
        this.setupService          = setupService;
        this.configService         = configService;
        this.clientFactory         = clientFactory;
        this.orchestrator          = orchestrator;
        this.systemSettingsService = systemSettingsService;
        this.authenticationManager = authenticationManager;
    }

    // ── Page endpoint ─────────────────────────────────────────────────────────

    /** Renders the setup wizard. Redirects to {@code /} if setup is already complete. */
    @GetMapping("/setup")
    public String setupWizard() {
        if (!setupService.isSetupRequired()) {
            return "redirect:/";
        }
        return "setup";
    }

    // ── API endpoints ─────────────────────────────────────────────────────────

    /** Returns whether setup is still required and whether the Gemini key is pre-configured. */
    @GetMapping("/api/setup/status")
    @ResponseBody
    public Map<String, Object> status() {
        return Map.of(
                "setupRequired",          setupService.isSetupRequired(),
                "geminiApiKeyConfigured",  configService.getConfig(AiProvider.GEMINI) != null
        );
    }

    /**
     * Creates the initial admin account.
     * On success the user is automatically logged into the current HTTP session.
     */
    @PostMapping("/api/setup/account")
    @ResponseBody
    public ResponseEntity<?> createAccount(@RequestBody AccountRequest req,
                                           HttpServletRequest httpRequest) {
        log.debug("ENTER createAccount: [username={}]", req.username());
        if (!setupService.isSetupRequired()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Setup is already complete."));
        }
        if (req.username() == null || req.username().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required."));
        }
        if (req.password() == null || req.password().length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", "Password must be at least 8 characters."));
        }
        if (!req.password().equals(req.confirmPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Passwords do not match."));
        }
        try {
            setupService.createAdminUser(req.username(), req.password());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        // Auto-login so subsequent wizard steps use the authenticated session
        autoLogin(req.username(), req.password(), httpRequest);
        log.info("Admin account created during setup: [username={}]", req.username());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * Saves the provider credentials, invalidates the discovery cache, and returns
     * the list of discovered models.  Returns an error body if no models are found.
     */
    @PostMapping("/api/setup/ai-provider/validate")
    @ResponseBody
    public ResponseEntity<?> validateProvider(@RequestBody ProviderValidateRequest req) {
        log.debug("ENTER validateProvider: [provider={}]", req.provider());
        AiProvider provider = resolveProvider(req.provider());
        if (provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown provider: " + req.provider()));
        }
        // Persist credentials so the discovery provider can pick them up
        configService.saveConfig(provider, req.apiKey(), req.baseUrl(), null, true);
        clientFactory.invalidate(provider);
        orchestrator.invalidate(provider.name().toLowerCase());
        List<DiscoveredModel> models = orchestrator.discoverForProvider(provider.name().toLowerCase());
        if (models.isEmpty()) {
            return ResponseEntity.ok(Map.of("error", "No models found — please check your credentials."));
        }
        log.info("Setup provider validation succeeded [provider={}, models={}]", provider, models.size());
        return ResponseEntity.ok(Map.of("models", models));
    }

    /**
     * Persists the final provider configuration and, when requested, sets it
     * as the global default provider/model for all new sessions.
     */
    @PostMapping("/api/setup/ai-provider")
    @ResponseBody
    public ResponseEntity<?> saveProvider(@RequestBody ProviderSaveRequest req) {
        log.debug("ENTER saveProvider: [provider={}, model={}]", req.provider(), req.defaultModel());
        AiProvider provider = resolveProvider(req.provider());
        if (provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown provider: " + req.provider()));
        }
        configService.saveConfig(provider, req.apiKey(), req.baseUrl(), req.defaultModel(), true);
        if (req.setAsGlobal() && req.defaultModel() != null && !req.defaultModel().isBlank()) {
            systemSettingsService.setGlobal(provider.name(), req.defaultModel());
        }
        log.info("Provider configured during setup [provider={}, model={}, global={}]",
                provider, req.defaultModel(), req.setAsGlobal());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AiProvider resolveProvider(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            AiProvider p = AiProvider.valueOf(name.toUpperCase());
            return (p == AiProvider.BACKGROUND_SCHEDULER || p == AiProvider.ANTHROPIC) ? null : p;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void autoLogin(String username, String password, HttpServletRequest request) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            HttpSession session = request.getSession(true);
            session.setAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
            log.debug("Auto-login succeeded for setup user: {}", username);
        } catch (AuthenticationException e) {
            log.warn("Auto-login failed after account creation (user will log in manually): {}", e.getMessage());
        }
    }

    // ── Request DTOs ─────────────────────────────────────────────────────────

    record AccountRequest(String username, String password, String confirmPassword) {}

    record ProviderValidateRequest(String provider, String apiKey, String baseUrl) {}

    record ProviderSaveRequest(String provider, String apiKey, String baseUrl,
                               String defaultModel, boolean setAsGlobal) {}
}
