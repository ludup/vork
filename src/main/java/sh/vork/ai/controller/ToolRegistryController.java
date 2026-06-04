package sh.vork.ai.controller;

import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import sh.vork.ai.registry.ToolDescriptor;
import sh.vork.ai.registry.ToolRegistry;

/**
 * Management endpoint that exposes the registered tool catalog.
 *
 * <p>Operators use this endpoint to discover valid Spring bean IDs for
 * {@link sh.vork.ai.agent.AgentTemplate#allowedTools()}.
 *
 * <pre>
 * GET /api/management/tools
 * </pre>
 */
@RestController
@RequestMapping("/api/management/tools")
public class ToolRegistryController {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryController.class);

    private final ToolRegistry toolRegistry;

    public ToolRegistryController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Returns all registered tool descriptors.
     *
     * @return 200 with the full tool catalog
     */
    @GetMapping
    public ResponseEntity<Collection<ToolDescriptor>> listTools() {
        log.debug("ENTER listTools");
        Collection<ToolDescriptor> tools = toolRegistry.getAvailableTools();
        log.debug("EXIT listTools: {} tools", tools.size());
        return ResponseEntity.ok(tools);
    }
}
