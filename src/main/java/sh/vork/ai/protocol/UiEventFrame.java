package sh.vork.ai.protocol;

import java.util.Map;

/**
 * Generic event envelope sent between backend and frontend over WebSocket/REST.
 */
public record UiEventFrame(
        String eventId,
        String type,
        String intent,
        Map<String, Object> payload
) {}
