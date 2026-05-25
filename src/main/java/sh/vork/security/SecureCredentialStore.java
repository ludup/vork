package sh.vork.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import sh.vork.ai.security.encrypt.EncryptionService;
import sh.vork.database.DatabaseRepository;
import sh.vork.database.SearchQuery;

/**
 * Placeholder credential store.
 *
 * <p>Secrets are kept in-memory only and are scoped by user + secret name.
 */
@Service
public class SecureCredentialStore {

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private DatabaseRepository<Secret> secretRepository;

    public void saveSecret(VorkUser user, String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("Secret value must not be null");
        }

        secretRepository.save(new Secret(
            null,
            user.uuid(),
            key,
            encryptionService.encrypt(value)
        ));
    }

    public String getSecret(VorkUser user, String key) {
        Secret secret = secretRepository.get(
            SearchQuery.eq("userUuid", user.uuid()),
            SearchQuery.eq(key, key));

        if (secret == null) {
            return null;
        }
        return encryptionService.decrypt(secret.encryptedPayload());
    }
}