package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code createSshConnection} AI tool.
 *
 * Connection details (hostname, port, username) and credentials are
 * collected out-of-band via a secure form and never appear in the LLM
 * conversation history.  The tool accepts only an optional friendly alias
 * so the AI can label the connection from context without the user having
 * to re-type it in the form.
 */
public record SshCreateConnectionRequest(

        @JsonProperty(required = false, value = "alias")
        @JsonPropertyDescription(
                "Optional friendly label for the saved connection "
                + "(e.g. 'production', 'dev-server'). "
                + "Leave blank to use the default username@host label.")
        String alias

) {}
