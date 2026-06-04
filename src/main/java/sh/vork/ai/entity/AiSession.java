package sh.vork.ai.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.jadaptive.orm.DatabaseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An AI chat session tied to an HTTP session.
 *
 * <p>Messages are embedded directly inside the document so that the full
 * conversation is loaded in a single MongoDB read.  The {@code uuid} is
 * the HTTP session ID, which allows {@link sh.vork.ai.service.ChatService}
 * to look up the session without a secondary index.
 *
 * @param uuid      HTTP session ID (also the MongoDB {@code _id})
 * @param provider  name of the {@link sh.vork.ai.AiProvider} in use
 * @param originMode execution origin mode for this session
 * @param username  owning principal for the session
 * @param name      human-friendly session label (non-unique)
 * @param createdAt epoch-milliseconds when the session was created
 * @param currentRoundCount number of autonomous background rounds already executed
 * @param messages  ordered list of conversation turns
 * @param environmentVariables live session environment variables for the active session
 * @param status    lifecycle state enum for autonomous/background execution tracking
 * @param agentTemplateStack ordered stack of {@link sh.vork.ai.agent.AgentTemplate} UUIDs;
 *                           the last element is the currently active agent persona
 */
public record AiSession(
        String              uuid,
        String              provider,
    SessionOriginMode   originMode,
    String              username,
    String              name,
        long                createdAt,
    int                 currentRoundCount,
        List<AiChatMessage> messages,
        Map<String, String> environmentVariables,
    AiSessionStatus     status,
    List<String>        agentTemplateStack
) implements DatabaseEntity {

    public AiSession {
        if (originMode == null) {
            originMode = SessionOriginMode.WEB;
        }
        if (username == null || username.isBlank()) {
            username = "anonymous";
        }
        if (name == null || name.isBlank()) {
            name = "Untitled";
        }
        if (messages == null) {
            messages = List.of();
        }
        if (environmentVariables == null) {
            environmentVariables = defaultEnvironmentVariables();
        } else {
            environmentVariables = new ConcurrentHashMap<>(environmentVariables);
        }
        if (status == null) {
            status = AiSessionStatus.RUNNING;
        }
        if (agentTemplateStack == null) {
            agentTemplateStack = new ArrayList<>();
        } else {
            agentTemplateStack = new ArrayList<>(agentTemplateStack);
        }
    }

    /**
     * Returns the UUID of the currently active {@link sh.vork.ai.agent.AgentTemplate},
     * i.e. the last element on the stack, or {@code null} if the stack is empty.
     */
    @JsonIgnore
    public String getActiveAgentTemplateId() {
        if (agentTemplateStack.isEmpty()) {
            return null;
        }
        return agentTemplateStack.get(agentTemplateStack.size() - 1);
    }

    /**
     * Pushes {@code templateUuid} onto the agent-template stack, making it the
     * active agent for this session.
     */
    public void pushAgent(String templateUuid) {
        agentTemplateStack.add(templateUuid);
    }

    /**
     * Pops the top entry from the agent-template stack, reverting to the
     * previous persona.  A pop that would empty the stack below one entry is
     * silently ignored to protect the root concierge persona.
     */
    public void popAgent() {
        if (agentTemplateStack.size() > 1) {
            agentTemplateStack.remove(agentTemplateStack.size() - 1);
        }
    }

    public static ConcurrentHashMap<String, String> defaultEnvironmentVariables() {
        return new ConcurrentHashMap<>();
    }
}
