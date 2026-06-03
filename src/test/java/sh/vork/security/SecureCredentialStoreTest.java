package sh.vork.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jadaptive.orm.DatabaseEntity;
import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.RepositoryFactory;
import com.jadaptive.orm.mock.MapDatabaseRepository;
import sh.vork.ai.security.encrypt.EncryptionService;

class SecureCredentialStoreTest {

    private SecureCredentialStore store;

    @BeforeEach
    void setUp() {
        EncryptionService enc = mock(EncryptionService.class);
        when(enc.encrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(enc.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        store = new SecureCredentialStore(
                new RepositoryFactory() {
                    @Override
                    public <T extends DatabaseEntity> DatabaseRepository<T> create(Class<T> entityClass) {
                        return new MapDatabaseRepository<>(entityClass);
                    }
                },
                enc);
    }

    @Test
    void savesAndGetsSecretForUserAndName() {
        VorkUser user = new VorkUser("alice", "hash", "USER", 0L, 0L);

        store.saveSecret(user, "apiKey", "secret-123");

        assertEquals("secret-123", store.getSecret(user, "apiKey"));
    }

    @Test
    void returnsNullWhenSecretDoesNotExist() {
        VorkUser user = new VorkUser("alice", "hash", "USER", 0L, 0L);

        assertNull(store.getSecret(user, "missing"));
    }

    @Test
    void scopesSecretsByUserAndName() {
        VorkUser alice = new VorkUser("alice", "hash", "USER", 0L, 0L);
        VorkUser bob = new VorkUser("bob", "hash", "USER", 0L, 0L);

        store.saveSecret(alice, "token", "alice-token");
        store.saveSecret(bob, "token", "bob-token");
        store.saveSecret(alice, "other", "alice-other");

        assertEquals("alice-token", store.getSecret(alice, "token"));
        assertEquals("bob-token", store.getSecret(bob, "token"));
        assertEquals("alice-other", store.getSecret(alice, "other"));
    }
}