package sh.vork.ai.context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ThreadLocalExecutionContext {

    private static final ThreadLocal<String> SESSION_UUID = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Object>> CONTEXT =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

    private ThreadLocalExecutionContext() {
    }

    public static void bindSessionUuid(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            SESSION_UUID.remove();
            return;
        }
        SESSION_UUID.set(sessionUuid);
    }

    public static String getSessionUuid() {
        return SESSION_UUID.get();
    }

    public static void put(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        CONTEXT.get().put(key, value);
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
}