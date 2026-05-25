package sh.vork.security;

import sh.vork.database.DatabaseEntity;

public record Secret(
    String uuid,      // The ID of the secret
    String userUuid,  // The VorkNode UUID this belongs to
    String key,
    String encryptedPayload
) implements DatabaseEntity {}