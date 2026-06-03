package sh.vork.security;

import com.jadaptive.orm.DatabaseEntity;

public record Secret(
    String uuid,      // The ID of the secret
    String userUuid,  // The VorkNode UUID this belongs to
    String key,
    String encryptedPayload
) implements DatabaseEntity {}