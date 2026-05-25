package sh.vork.ssh;

import sh.vork.database.DatabaseEntity;

/**
 * Persistent SSH node identity scoped to a Vork user.
 */
public record VorkNode(
        String uuid,
        String ownerUserUuid,
        String host,
        String username,
        long createdAt,
        long updatedAt,
        String verifiedHostKey
) implements DatabaseEntity {
}
