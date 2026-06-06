package sh.vork.ai.discovery;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for dynamic model discovery.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/ai/models}             — all discovered models (all providers)</li>
 *   <li>{@code GET /api/ai/models/{provider}}  — models for one provider (e.g. {@code openai})</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/models")
public class ModelDiscoveryController {

    private static final Logger log = LoggerFactory.getLogger(ModelDiscoveryController.class);

    private final ModelDiscoveryOrchestrator orchestrator;

    public ModelDiscoveryController(ModelDiscoveryOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public List<DiscoveredModel> getAllModels() {
        log.debug("ENTER getAllModels");
        List<DiscoveredModel> models = orchestrator.getAllDiscoveredModels();
        log.debug("EXIT getAllModels: {} model(s)", models.size());
        return models;
    }

    @GetMapping("/{provider}")
    public List<DiscoveredModel> getModelsForProvider(@PathVariable String provider) {
        log.debug("ENTER getModelsForProvider: [provider={}]", provider);
        List<DiscoveredModel> models = orchestrator.discoverForProvider(provider);
        log.debug("EXIT getModelsForProvider: {} model(s)", models.size());
        return models;
    }
}
