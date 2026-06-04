package sh.vork.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.jadaptive.orm.DatabaseRepository;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.function.DelegateToAgentRequest;

/**
 * System tool that activates a named sub-agent persona on the current session.
 *
 * <p>On invocation the target {@link AgentTemplate} is looked up by UUID and
 * pushed onto the session's {@code agentTemplateStack}.  Subsequent AI calls
 * in the same session will inject the template's {@code systemPrompt} and
 * restrict the available tools to its {@code allowedTools} list until the
 * complementary {@link CompleteAgentTaskTool} reverts the stack.
 */
@Component
public class DelegateToAgentTool {

    private static final Logger log = LoggerFactory.getLogger(DelegateToAgentTool.class);

    private final DatabaseRepository<AiSession>     sessionRepo;
    private final DatabaseRepository<AgentTemplate> agentTemplateRepo;

    public DelegateToAgentTool(DatabaseRepository<AiSession> sessionRepo,
                                DatabaseRepository<AgentTemplate> agentTemplateRepo) {
        this.sessionRepo       = sessionRepo;
        this.agentTemplateRepo = agentTemplateRepo;
    }

    public String execute(DelegateToAgentRequest req) {
        log.debug("ENTER execute: agentTemplateUuid={}", req == null ? null : req.agentTemplateUuid());

        if (req == null || req.agentTemplateUuid() == null || req.agentTemplateUuid().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"agentTemplateUuid is required\"}";
        }

        AgentTemplate template = agentTemplateRepo.get(req.agentTemplateUuid());
        if (template == null) {
            log.warn("DelegateToAgentTool: agent template not found [uuid={}]", req.agentTemplateUuid());
            return "{\"status\":\"error\",\"message\":\"Agent template not found: " + req.agentTemplateUuid() + "\"}";
        }

        String sessionUuid = ToolExecutionContext.getSessionUuid();
        if (sessionUuid == null || sessionUuid.isBlank()) {
            log.warn("DelegateToAgentTool: no session UUID in ToolExecutionContext");
            return "{\"status\":\"error\",\"message\":\"No active session\"}";
        }

        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            log.warn("DelegateToAgentTool: session not found [uuid={}]", sessionUuid);
            return "{\"status\":\"error\",\"message\":\"Session not found: " + sessionUuid + "\"}";
        }

        session.pushAgent(req.agentTemplateUuid());
        sessionRepo.save(session);

        log.info("Delegated to agent [template={}, name={}, session={}]",
                req.agentTemplateUuid(), template.name(), sessionUuid);
        log.debug("EXIT execute: DELEGATION_SUCCESSFUL agentTemplateUuid={}", req.agentTemplateUuid());

        return "{\"status\":\"DELEGATION_SUCCESSFUL\",\"active_template_id\":\"" + req.agentTemplateUuid()
                + "\",\"agent_name\":\"" + template.name() + "\"}";
    }
}
