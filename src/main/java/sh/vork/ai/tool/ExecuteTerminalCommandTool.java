package sh.vork.ai.tool;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.entity.AiSession;
import sh.vork.ai.function.ExecuteTerminalCommandRequest;
import sh.vork.ai.security.VisualizableTool;
import sh.vork.ai.terminal.TerminalStreamRouter;
import com.jadaptive.orm.DatabaseRepository;

@Component
public class ExecuteTerminalCommandTool extends AbstractTerminalTool implements VisualizableTool {

    ObjectMapper objectMapper = new ObjectMapper();

    public ExecuteTerminalCommandTool(TerminalStreamRouter terminalStreamRouter,
                                      DatabaseRepository<AiSession> aiSessionRepository) {
        super(terminalStreamRouter, aiSessionRepository);
    }

    public String execute(ExecuteTerminalCommandRequest req) {
        if (req == null || req.command() == null || req.command().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"command is required\"}";
        }
        return executeTerminalCommand(req.host(), req.command());
    }

    @Override
    public String formatAuthorizationDetails(String argumentsJson) {
        try {
                String command = objectMapper.readTree(argumentsJson)
                        .path("command")
                        .asText();
                if (command == null || command.isBlank()) {
                    return argumentsJson;
                }
                return command;
            } catch (Exception ex) {
                return argumentsJson;
            }
    }
}