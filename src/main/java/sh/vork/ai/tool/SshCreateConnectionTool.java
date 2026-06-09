package sh.vork.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.function.SshCreateConnectionRequest;
import sh.vork.ai.security.VisualizableTool;
import sh.vork.ssh.VirtualSshService;

@Component
public class SshCreateConnectionTool implements VisualizableTool {

    private static final Logger log = LoggerFactory.getLogger(SshCreateConnectionTool.class);

    private final VirtualSshService virtualSshService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SshCreateConnectionTool(VirtualSshService virtualSshService) {
        this.virtualSshService = virtualSshService;
    }

    public String execute(SshCreateConnectionRequest req) {
        log.debug("ENTER SshCreateConnectionTool.execute: alias={}", req != null ? req.alias() : null);
        String alias = (req != null && req.alias() != null && !req.alias().isBlank())
                ? req.alias().trim() : null;
        try {
            String result = virtualSshService.createAndSaveConnection(alias);
            log.debug("EXIT SshCreateConnectionTool.execute: result={}", result);
            return result;
        } catch (ToolSuspensionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("createSshConnection failed unexpectedly: {}", e.getMessage(), e);
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    @Override
    public String formatAuthorizationDetails(String argumentsJson) {
        try {
            String alias = objectMapper.readTree(argumentsJson).path("alias").asText();
            return (alias != null && !alias.isBlank()) ? "Create SSH connection: " + alias : "Create SSH connection";
        } catch (Exception ex) {
            return "Create SSH connection";
        }
    }
}
