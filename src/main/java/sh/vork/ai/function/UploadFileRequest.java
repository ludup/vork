package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record UploadFileRequest(
        @JsonProperty(required = true, value = "hostOrAlias")
        @JsonPropertyDescription("SSH host string or alias of an active connection established with connectSsh.")
        String hostOrAlias,

        @JsonProperty(required = true, value = "filename")
        @JsonPropertyDescription("UUID or filename of a file already in Vork's file storage service, or an absolute local filesystem path. Storage-service files are uploaded immediately; local paths require explicit authorisation.")
        String filename,

        @JsonProperty(required = false, value = "remotePath")
        @JsonPropertyDescription("Destination path on the remote host. If omitted, the file is uploaded to the remote home directory with its original name.")
        String remotePath
) {}
