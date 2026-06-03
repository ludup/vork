package sh.vork.security;

import com.jadaptive.orm.DatabaseEntity;

/**
 * User entity for persistent credential storage.
 * Persisted to MongoDB collection: vork_user
 */
public record VorkUser(
    String uuid,           // username as unique ID
    String passwordHash,   // BCrypt-encoded password
    String role,           // ADMIN, USER, etc.
    long createdAt,
    long updatedAt
) implements DatabaseEntity {}
