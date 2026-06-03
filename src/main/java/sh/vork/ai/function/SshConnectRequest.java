package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record SshConnectRequest(
        @JsonProperty(required = true, value = "host")
        @JsonPropertyDescription("SSH host to connect to. Accepted formats: user@host:port, user@host, host:port, or just host.")
        String host,

        @JsonProperty(required = false, value = "alias")
        @JsonPropertyDescription("Short alias to identify this connection. If blank or omitted, the host string is used as the alias.")
        String alias
) {}
