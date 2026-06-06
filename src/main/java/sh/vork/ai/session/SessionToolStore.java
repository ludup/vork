package sh.vork.ai.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * In-memory store of session-scoped {@link ToolCallback} instances.
 *
 * <p>Some tools are not appropriate for global exposure (e.g. they are annotated
 * with {@link sh.vork.ai.registry.Hidden}) and therefore excluded from the shared
 * {@code securedToolCallbackMap}.  Instead, they are registered here for specific
 * sessions and merged into the tool set when an AI request is built for that session.
 *
 * <p>Typical usage:
 * <ol>
 *   <li>Before starting background execution for a session, call
 *       {@link #addTool(String, ToolCallback)} to inject the desired tool.</li>
 *   <li>{@link sh.vork.ai.service.AiOrchestrationService} queries
 *       {@link #getTools(String)} on every request to merge the session-scoped
 *       tools into the final tool set passed to the AI model.</li>
 *   <li>When the session is no longer active, call {@link #clearSession(String)}
 *       to release the memory.</li>
 * </ol>
 *
 * <p>This component is thread-safe; the inner lists are protected by the
 * per-session lock provided by {@link ConcurrentHashMap#compute}.
 */
@Component
public class SessionToolStore {

    private static final Logger log = LoggerFactory.getLogger(SessionToolStore.class);

    private final ConcurrentHashMap<String, List<ToolCallback>> store = new ConcurrentHashMap<>();

    /**
     * Registers a tool callback for the given session.  The same callback can be
     * added multiple times without producing duplicates — it is checked by
     * {@link ToolCallback#getToolDefinition()} name equality.
     *
     * @param sessionUuid the session to attach the tool to
     * @param callback    the tool callback to inject
     */
    public void addTool(String sessionUuid, ToolCallback callback) {
        String toolName = callback.getToolDefinition().name();
        store.compute(sessionUuid, (k, existing) -> {
            List<ToolCallback> list = (existing != null) ? existing : new ArrayList<>();
            boolean alreadyPresent = list.stream()
                    .anyMatch(t -> toolName.equals(t.getToolDefinition().name()));
            if (!alreadyPresent) {
                list.add(callback);
            }
            return list;
        });
        log.debug("Session tool registered [session={}, tool={}]", sessionUuid, toolName);
    }

    /**
     * Returns an unmodifiable snapshot of the tools registered for this session.
     * Returns an empty list if none are registered.
     *
     * @param sessionUuid the session to query
     * @return immutable list of registered tool callbacks
     */
    public List<ToolCallback> getTools(String sessionUuid) {
        if (sessionUuid == null) return Collections.emptyList();
        List<ToolCallback> list = store.get(sessionUuid);
        return (list == null) ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    /**
     * Removes all tool registrations for the given session.
     * Safe to call when the session is no longer active.
     *
     * @param sessionUuid the session to clear
     */
    public void clearSession(String sessionUuid) {
        if (sessionUuid == null) return;
        store.remove(sessionUuid);
        log.debug("Session tool store cleared [session={}]", sessionUuid);
    }
}
