package sh.vork.security;

import java.util.UUID;

import org.springframework.stereotype.Service;

import sh.vork.ai.security.encrypt.EncryptionService;
import sh.vork.database.DatabaseRepository;
import sh.vork.database.DatabaseRepositoryFactory;
import sh.vork.database.SearchQuery;

/**
 * Placeholder credential store.
 *
 * <p>Secrets are kept in-memory only and are scoped by user + secret name.
 */
@Service
public class SecureCredentialStore {

    private final EncryptionService encryptionService;

    private final  DatabaseRepository<Secret> secretRepository;

    public SecureCredentialStore( DatabaseRepositoryFactory factory, EncryptionService encryptionService ) {
        this.secretRepository = factory.create(Secret.class);
        this.encryptionService = encryptionService;
    }
    public void saveSecret(VorkUser user, String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("Secret value must not be null");
        }

        String uuid = UUID.nameUUIDFromBytes((user.uuid() + ":" + key).getBytes()).toString();
        secretRepository.save(new Secret(
            uuid,
            user.uuid(),
            key,
            encryptionService.encrypt(value)
        ));
    }

    public String getSecret(VorkUser user, String key) {
        Secret secret = secretRepository.get(
            SearchQuery.eq("userUuid", user.uuid()),
            SearchQuery.eq("key", key));

        if (secret == null) {
            return null;
        }
        return encryptionService.decrypt(secret.encryptedPayload());
    }
}