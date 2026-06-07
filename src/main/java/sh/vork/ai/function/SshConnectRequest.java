package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record SshConnectRequest(
        @JsonProperty(required = true, value = "host")
        @JsonPropertyDescription("SSH host to connect to. Accepted formats: user@host:port, user@host, host:port, or just host. The user@ prefix is an SSH login username (e.g. root, ubuntu) — never a friendly label or alias.")
        String host,

        @JsonProperty(required = false, value = "alias")
        @JsonPropertyDescription("Friendly label for this connection used in later tool calls. When the user says 'connect to X as Y' or 'call it Y', set alias=Y. Do NOT put Y in the host field.")
        String alias
) {}
