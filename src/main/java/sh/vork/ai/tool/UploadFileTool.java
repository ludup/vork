package sh.vork.ai.tool;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.sshtools.client.sftp.SftpClient;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.function.UploadFileRequest;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.SearchQuery;
import com.jadaptive.orm.SortOrder;
import sh.vork.ssh.VirtualSshService;
import sh.vork.storage.FileStorageService;
import sh.vork.storage.StoredFile;

@Component
public class UploadFileTool {

    private final VirtualSshService virtualSshService;
    private final FileStorageService fileStorageService;
    private final DatabaseRepository<StoredFile> storedFileRepository;

    public UploadFileTool(VirtualSshService virtualSshService,
                          FileStorageService fileStorageService,
                          DatabaseRepository<StoredFile> storedFileRepository) {
        this.virtualSshService = virtualSshService;
        this.fileStorageService = fileStorageService;
        this.storedFileRepository = storedFileRepository;
    }

    public String execute(UploadFileRequest req) {
        if (req == null || req.hostOrAlias() == null || req.hostOrAlias().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"hostOrAlias is required\"}";
        }
        if (req.filename() == null || req.filename().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"filename is required\"}";
        }

        String sessionId = resolveSessionUuid();
        String filename = req.filename().trim();

        // Try to locate the file in the storage service (by UUID first, then by name)
        StoredFile storedFile = resolveStoredFile(filename);

        if (storedFile != null) {
            // File is in Vork's storage — upload immediately, no authorization needed
            try {
                SftpClient sftp = virtualSshService.getSftpClient(sessionId, req.hostOrAlias());
                String remoteDest = resolveRemotePath(req.remotePath(), storedFile.originalName());
                try (InputStream in = fileStorageService.getContent(storedFile.uuid())) {
                    sftp.put(in, remoteDest, null);
                }
                return "{\"status\":\"ok\",\"source\":\"storage\",\"uuid\":\"" + storedFile.uuid()
                        + "\",\"remote\":\"" + remoteDest + "\"}";
            } catch (ToolSuspensionException e) {
                throw e;
            } catch (Exception e) {
                return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
            }
        }

        // Treat filename as a local filesystem path — requires authorization
        Object approval = ToolExecutionContext.get("UPLOAD_LOCAL_AUTHORIZED");
        if (!"true".equals(approval)) {
            throw localPathAuthorizationPrompt(filename);
        }

        try {
            SftpClient sftp = virtualSshService.getSftpClient(sessionId, req.hostOrAlias());
            java.io.File localFile = new java.io.File(filename);
            if (!localFile.exists() || !localFile.isFile()) {
                return "{\"status\":\"error\",\"message\":\"Local file not found: " + filename + "\"}";
            }
            String remoteDest = resolveRemotePath(req.remotePath(), localFile.getName());
            try (InputStream in = new java.io.FileInputStream(localFile)) {
                sftp.put(in, remoteDest, null);
            }
            return "{\"status\":\"ok\",\"source\":\"local\",\"path\":\"" + filename
                    + "\",\"remote\":\"" + remoteDest + "\"}";
        } catch (ToolSuspensionException e) {
            throw e;
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * Tries to find the file in storage by UUID (direct lookup), then by original name.
     */
    private StoredFile resolveStoredFile(String filename) {
        // Try exact UUID lookup first
        StoredFile byUuid = fileStorageService.getMetadata(filename);
        if (byUuid != null) return byUuid;

        // Try by original name
        try (Stream<StoredFile> stream = storedFileRepository.search(
                0, 1, "uploadedAt", SortOrder.DESC,
                SearchQuery.eq("originalName", filename))) {
            return stream.findFirst().orElse(null);
        }
    }

    private static String resolveRemotePath(String remotePath, String localName) {
        if (remotePath == null || remotePath.isBlank()) {
            return localName;
        }
        String rp = remotePath.trim();
        // If it looks like a directory path (ends with /), append the filename
        if (rp.endsWith("/")) {
            return rp + localName;
        }
        return rp;
    }

    private static ToolSuspensionException localPathAuthorizationPrompt(String localPath) {
        InteractionFormSchema schema = new InteractionFormSchema(
                "AUTHORIZE_TOOL",
                "Local File Upload Authorization",
                "Authorize reading from the local filesystem path and uploading to the remote host: " + localPath,
                List.of(new FormField(
                        "UPLOAD_LOCAL_AUTHORIZED",
                        "CHECKBOX",
                        "Authorize local file upload",
                        "I authorize reading this file from the local filesystem and uploading it.",
                        true,
                        FieldSource.CONTEXT,
                        Collections.emptyList())),
                List.of(
                        new FormAction("ONCE", "Authorize", "warning"),
                        new FormAction("DENIED", "Cancel", "danger")));
        return new ToolSuspensionException("sshUploadFile", "{}",
                "Authorization required to read file from local filesystem.", schema);
    }

    private String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            throw new IllegalStateException("No sessionUuid available in execution context");
        }
        return sessionUuid;
    }
}
