package sh.vork.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.jadaptive.orm.DatabaseRepository;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.function.CompleteAgentTaskRequest;

/**
 * System tool that reverts the session to the previous agent persona.
 *
 * <p>Pops the top entry from the session's {@code agentTemplateStack}.  If the
 * stack would shrink below one entry the pop is silently ignored, ensuring the
 * root (concierge) persona is never removed.
 */
@Component
public class CompleteAgentTaskTool {

    private static final Logger log = LoggerFactory.getLogger(CompleteAgentTaskTool.class);

    private final DatabaseRepository<AiSession> sessionRepo;

    public CompleteAgentTaskTool(DatabaseRepository<AiSession> sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    public String execute(CompleteAgentTaskRequest req) {
        log.debug("ENTER execute");

        String sessionUuid = ToolExecutionContext.getSessionUuid();
        if (sessionUuid == null || sessionUuid.isBlank()) {
            log.warn("CompleteAgentTaskTool: no session UUID in ToolExecutionContext");
            return "{\"status\":\"error\",\"message\":\"No active session\"}";
        }

        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) {
            log.warn("CompleteAgentTaskTool: session not found [uuid={}]", sessionUuid);
            return "{\"status\":\"error\",\"message\":\"Session not found: " + sessionUuid + "\"}";
        }

        String previousTop = session.getActiveAgentTemplateId();
        session.popAgent();
        sessionRepo.save(session);

        String newTop = session.getActiveAgentTemplateId();
        log.info("Completed agent task, reverted persona [session={}, from={}, to={}]",
                sessionUuid, previousTop, newTop);
        log.debug("EXIT execute: REVERSION_SUCCESSFUL");

        return "{\"status\":\"REVERSION_SUCCESSFUL\",\"previous_template_id\":\""
                + (previousTop == null ? "" : previousTop) + "\"}";
    }
}
