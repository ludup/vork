package sh.vork.ai.context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ToolExecutionContext {

    private static final ThreadLocal<String> SESSION_UUID = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Object>> CONTEXT =
            ThreadLocal.withInitial(ConcurrentHashMap::new);
    private static final ConcurrentHashMap<String, Map<String, Object>> PERSISTED_CONTEXTS = new ConcurrentHashMap<>();

    private ToolExecutionContext() {
    }

    public static void bindSessionUuid(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            clear();
            return;
        }

        SESSION_UUID.set(sessionUuid);
        CONTEXT.set(PERSISTED_CONTEXTS.computeIfAbsent(sessionUuid, ignored -> new ConcurrentHashMap<>()));
    }

    public static String getSessionUuid() {
        return SESSION_UUID.get();
    }

    public static void put(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }

        CONTEXT.get().put(key, value);
        String sessionUuid = SESSION_UUID.get();
        if (sessionUuid != null && !sessionUuid.isBlank()) {
            PERSISTED_CONTEXTS.put(sessionUuid, CONTEXT.get());
        }
    }

    public static void hydrate(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return;
        }

        Map<String, Object> current = CONTEXT.get();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            current.put(key, entry.getValue());
        }

        String sessionUuid = SESSION_UUID.get();
        if (sessionUuid != null && !sessionUuid.isBlank()) {
            PERSISTED_CONTEXTS.put(sessionUuid, current);
        }
    }

    public static Object get(String key) {
        return CONTEXT.get().get(key);
    }

    public static Map<String, Object> snapshot() {
        return Map.copyOf(CONTEXT.get());
    }

    public static void clear() {
        SESSION_UUID.remove();
        CONTEXT.remove();
    }

    public static void complete() {
        complete(getSessionUuid());
    }

    public static void complete(String sessionUuid) {
        if (sessionUuid != null && !sessionUuid.isBlank()) {
            PERSISTED_CONTEXTS.remove(sessionUuid);
        }
        clear();
    }

    public static boolean isBound() {
        return SESSION_UUID.get() != null && !SESSION_UUID.get().isBlank();
    }
}