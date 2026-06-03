package sh.vork.ai.tool;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.function.SshConnectRequest;
import sh.vork.ssh.VirtualSshService;

@Component
public class SshConnectTool {

    private final VirtualSshService virtualSshService;

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
}
