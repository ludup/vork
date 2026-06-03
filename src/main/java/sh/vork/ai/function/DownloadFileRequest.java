package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record DownloadFileRequest(
        @JsonProperty(required = true, value = "hostOrAlias")
        @JsonPropertyDescription("SSH host string or alias of an active connection established with connectSsh.")
        String hostOrAlias,

        @JsonProperty(required = true, value = "remotePath")
        @JsonPropertyDescription("Absolute path of the file to download from the remote host.")
        String remotePath,

        @JsonProperty(required = false, value = "localPath")
        @JsonPropertyDescription("Optional local filesystem path to save the downloaded file. If omitted or blank, the file is stored in Vork's file storage service with no extra authorisation needed.")
        String localPath
) {}
