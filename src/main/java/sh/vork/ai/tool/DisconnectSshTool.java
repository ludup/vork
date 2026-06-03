package sh.vork.ai.tool;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.function.DisconnectSshRequest;
import sh.vork.ssh.VirtualSshService;

@Component
public class DisconnectSshTool {

    private final VirtualSshService virtualSshService;

    public DisconnectSshTool(VirtualSshService virtualSshService) {
        this.virtualSshService = virtualSshService;
    }

    public String execute(DisconnectSshRequest req) {
        if (req == null || req.hostOrAlias() == null || req.hostOrAlias().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"hostOrAlias is required\"}";
        }
        String sessionId = resolveSessionUuid();
        try {
            virtualSshService.disconnect(sessionId, req.hostOrAlias().trim());
            return "SSH connection \"" + req.hostOrAlias().trim() + "\" has been closed.";
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
