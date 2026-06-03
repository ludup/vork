package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code disconnectSsh} tool.
 */
public record DisconnectSshRequest(
        @JsonProperty(required = true, value = "hostOrAlias")
        @JsonPropertyDescription("The alias or hostname of the SSH connection to close.")
        String hostOrAlias
) {}
