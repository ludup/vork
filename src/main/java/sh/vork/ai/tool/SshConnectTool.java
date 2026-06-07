  package sh.vork.ai.tool;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.function.SshConnectRequest;
import sh.vork.ai.security.VisualizableTool;
import sh.vork.ssh.VirtualSshService;

@Component
public class SshConnectTool implements VisualizableTool {

    private final VirtualSshService virtualSshService;
    private ObjectMapper objectMapper = new ObjectMapper();

    public SshConnectTool(VirtualSshService virtualSshService) {
        this.virtualSshService = virtualSshService;
    }

    public String execute(SshConnectRequest req) {
        if (req == null || req.host() == null || req.host().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"host is required\"}";
        }
        String host = req.host().trim();
        String alias = (req.alias() == null || req.alias().isBlank()) ? host : req.alias().trim();
        String sessionId = resolveSessionUuid();
        try {
            virtualSshService.connectAndCache(sessionId, host, alias);
            return "You are now connected to " + host + " with alias \"" + alias + "\".";
        } catch (ToolSuspensionException e) {
            throw e;
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

    @Override
    public String formatAuthorizationDetails(String argumentsJson) {
        try {
                String host = objectMapper.readTree(argumentsJson)
                        .path("host")
                        .asText();
                if (host == null || host.isBlank()) {
                    return argumentsJson;
                }
                return host;
            } catch (Exception ex) {
                return argumentsJson;
            }
    }
}
