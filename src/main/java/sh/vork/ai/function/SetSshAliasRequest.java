package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code setSshAlias} tool.
 */
public record SetSshAliasRequest(
        @JsonProperty(required = true, value = "hostOrAlias")
        @JsonPropertyDescription("The current alias or hostname identifying the connection to rename.")
        String hostOrAlias,

        @JsonProperty(required = true, value = "newAlias")
        @JsonPropertyDescription("The new short alias to assign to the connection, e.g. \"imac\" or \"prod\".")
        String newAlias
) {}
