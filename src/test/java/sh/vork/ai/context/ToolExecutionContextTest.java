package sh.vork.ai.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ToolExecutionContextTest {

    @AfterEach
    void clearContext() {
        ToolExecutionContext.complete("session-test");
        ToolExecutionContext.clear();
    }

    @Test
    void context_survivesSuspendAndIsRemovedOnComplete() {
        ToolExecutionContext.bindSessionUuid("session-test");
        ToolExecutionContext.put("username", "alice");

        assertEquals("alice", ToolExecutionContext.get("username"));

        ToolExecutionContext.clear();
        ToolExecutionContext.bindSessionUuid("session-test");

        assertEquals("alice", ToolExecutionContext.get("username"));

        ToolExecutionContext.complete();
        ToolExecutionContext.bindSessionUuid("session-test");

        assertNull(ToolExecutionContext.get("username"));
    }

    @Test
    void hydrate_restoresPersistedValuesAfterClear() {
        ToolExecutionContext.bindSessionUuid("session-test");
        ToolExecutionContext.hydrate(Map.of("HOST_KEY_VERIFICATION", "true", "ssh-node-username-example_com", "ubuntu"));

        ToolExecutionContext.clear();
        ToolExecutionContext.bindSessionUuid("session-test");

        assertEquals("true", ToolExecutionContext.get("HOST_KEY_VERIFICATION"));
        assertEquals("ubuntu", ToolExecutionContext.get("ssh-node-username-example_com"));
    }
}