package sh.vork.ai.tool;

import java.util.List;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.function.ListSshConnectionsRequest;
import sh.vork.ssh.VirtualSshService;
import sh.vork.ssh.VirtualSshService.SshConnectionInfo;

@Component
public class ListSshConnectionsTool {

    private final VirtualSshService virtualSshService;
    private final ObjectMapper objectMapper;

    public ListSshConnectionsTool(VirtualSshService virtualSshService, ObjectMapper objectMapper) {
        this.virtualSshService = virtualSshService;
        this.objectMapper = objectMapper;
    }

    public String execute(ListSshConnectionsRequest req) {
        String sessionId = resolveSessionUuid();
        try {
            List<SshConnectionInfo> connections = virtualSshService.listConnections(sessionId);
            if (connections.isEmpty()) {
                return "{\"connections\":[],\"message\":\"No active SSH connections.\"}";
            }
            return objectMapper.writeValueAsString(
                    new ConnectionListResponse(connections));
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            throw new IllegalStateException("No sessionUuid available in execution context");
        }
        return sessionUuid;
    }

    private record ConnectionListResponse(List<SshConnectionInfo> connections) {}
}
