package sh.vork.ai.tool;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.function.SetSshAliasRequest;
import sh.vork.ssh.VirtualSshService;

@Component
public class SetSshAliasTool {

    private final VirtualSshService virtualSshService;

    public SetSshAliasTool(VirtualSshService virtualSshService) {
        this.virtualSshService = virtualSshService;
    }

    public String execute(SetSshAliasRequest req) {
        if (req == null || req.hostOrAlias() == null || req.hostOrAlias().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"hostOrAlias is required\"}";
        }
        if (req.newAlias() == null || req.newAlias().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"newAlias is required\"}";
        }
        String sessionId = resolveSessionUuid();
        try {
            virtualSshService.setAlias(sessionId, req.hostOrAlias().trim(), req.newAlias().trim());
            return "Connection \"" + req.hostOrAlias().trim() + "\" has been renamed to \""
                    + req.newAlias().trim() + "\".";
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
