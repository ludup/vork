package sh.vork.ai.controller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import com.jadaptive.orm.DatabaseRepository;
import sh.vork.ai.agent.AgentTemplate;

/**
 * Page and REST API controller for the Agents management UI.
 *
 * <p>System agents (where {@link AgentTemplate#systemAgent()} is {@code true}) can
 * be viewed and edited but never deleted.
 */
@Controller
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final DatabaseRepository<AgentTemplate> agentRepository;

    public AgentController(DatabaseRepository<AgentTemplate> agentRepository) {
        this.agentRepository = agentRepository;
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/agents")
    public String agentsPage(Model model) {
        log.debug("ENTER agentsPage");
        List<AgentTemplate> agents;
        try (var stream = agentRepository.list(0, Integer.MAX_VALUE)) {
            agents = stream.collect(Collectors.toList());
        }
        model.addAttribute("agents", agents);
        return "agents";
    }

    // ── REST: list ────────────────────────────────────────────────────────────

    @GetMapping("/api/agents")
    @ResponseBody
    public List<AgentTemplate> listAgents() {
        log.debug("ENTER listAgents");
        try (var stream = agentRepository.list(0, Integer.MAX_VALUE)) {
            return stream.collect(Collectors.toList());
        }
    }

    // ── REST: create ──────────────────────────────────────────────────────────

    @PostMapping("/api/agents")
    @ResponseBody
    public ResponseEntity<?> createAgent(@RequestBody AgentRequest req) {
        log.debug("ENTER createAgent: [name={}]", req.name());
        String err = validate(req);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));

        AgentTemplate agent = new AgentTemplate(
                UUID.randomUUID().toString(),
                req.name(),
                req.systemPrompt() != null ? req.systemPrompt() : "",
                req.allowedTools() != null ? List.copyOf(req.allowedTools()) : List.of(),
                false);
        agentRepository.save(agent);
        log.info("Agent created [id={}, name={}]", agent.uuid(), agent.name());
        return ResponseEntity.ok(agent);
    }

    // ── REST: update ──────────────────────────────────────────────────────────

    @PutMapping("/api/agents/{id}")
    @ResponseBody
    public ResponseEntity<?> updateAgent(@PathVariable String id, @RequestBody AgentRequest req) {
        log.debug("ENTER updateAgent: [id={}]", id);
        AgentTemplate existing = agentRepository.get(id);
        if (existing == null) return ResponseEntity.notFound().build();

        String err = validate(req);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));

        if (existing.systemAgent()) {
            boolean instructionsChanged = !Objects.equals(
                    req.systemPrompt() != null ? req.systemPrompt() : "",
                    existing.systemPrompt());
            boolean toolsChanged = req.allowedTools() != null
                    && !req.allowedTools().equals(existing.allowedTools());
            if (instructionsChanged || toolsChanged) {
                log.warn("Refused to update instructions/tools of system agent [id={}]", id);
                return ResponseEntity.status(403).body(Map.of(
                        "error", "System agent instructions and tools are managed by the seeder "
                               + "and cannot be edited here. Update the code and restart to apply changes."));
            }
        }

        AgentTemplate updated = new AgentTemplate(
                id,
                req.name(),
                req.systemPrompt() != null ? req.systemPrompt() : "",
                req.allowedTools() != null ? List.copyOf(req.allowedTools()) : List.of(),
                existing.systemAgent()); // preserve system flag
        agentRepository.save(updated);
        log.info("Agent updated [id={}, name={}]", id, req.name());
        return ResponseEntity.ok(updated);
    }

    // ── REST: delete ──────────────────────────────────────────────────────────

    @DeleteMapping("/api/agents/{id}")
    @ResponseBody
    public ResponseEntity<?> deleteAgent(@PathVariable String id) {
        log.debug("ENTER deleteAgent: [id={}]", id);
        AgentTemplate existing = agentRepository.get(id);
        if (existing == null) return ResponseEntity.notFound().build();
        if (existing.systemAgent()) {
            log.warn("Refused to delete system agent [id={}]", id);
            return ResponseEntity.status(403)
                    .body(Map.of("error", "System agents cannot be deleted."));
        }

        agentRepository.delete(id);
        log.info("Agent deleted [id={}]", id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String validate(AgentRequest req) {
        if (req.name() == null || req.name().isBlank()) return "Name is required.";
        return null;
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    record AgentRequest(
            String       name,
            String       systemPrompt,
            List<String> allowedTools
    ) {}
}
