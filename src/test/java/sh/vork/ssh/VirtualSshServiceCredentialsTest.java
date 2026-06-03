package sh.vork.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormField;
import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.mock.MapDatabaseRepository;
import sh.vork.security.SecureCredentialStore;
import sh.vork.security.VorkUser;

class VirtualSshServiceCredentialsTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        ToolExecutionContext.clear();
    }

    @Test
    void connectClient_withoutStoredCredentials_requestsSecretsUsingNodeUuidKeys() throws Exception {
        SecureCredentialStore credentialStore = Mockito.mock(SecureCredentialStore.class);
                DatabaseRepository<VorkNode> nodeRepository = new MapDatabaseRepository<>(VorkNode.class);
                nodeRepository.save(new VorkNode("node-123", "alice", "example.com", "system", 1L, 1L, ""));
        when(credentialStore.getSecret(any(VorkUser.class), any(String.class))).thenReturn(null);

        VirtualSshService service = new VirtualSshService();
        Field credentialStoreField = VirtualSshService.class.getDeclaredField("credentialStore");
        credentialStoreField.setAccessible(true);
        credentialStoreField.set(service, credentialStore);
        Field nodeRepositoryField = VirtualSshService.class.getDeclaredField("nodeRepository");
        nodeRepositoryField.setAccessible(true);
        nodeRepositoryField.set(service, nodeRepository);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        ToolSuspensionException ex = assertThrows(
                ToolSuspensionException.class,
                () -> service.connectClient("system", "example.com", 1));

        verify(credentialStore).getSecret(
                eq(new VorkUser("alice", "", "USER", 0L, 0L)),
                eq("ssh-password-node-123"));
        verify(credentialStore).getSecret(
                eq(new VorkUser("alice", "", "USER", 0L, 0L)),
                eq("ssh-key-node-123"));
        verify(credentialStore).getSecret(
                eq(new VorkUser("alice", "", "USER", 0L, 0L)),
                eq("ssh-passphrase-node-123"));

        assertEquals("executeTerminalCommand", ex.getToolName());
        assertEquals("AUTHORIZE_TOOL", ex.getFormSchema().intent());
        assertEquals("SSH Credentials Required", ex.getFormSchema().title());

        List<FormField> fields = ex.getFormSchema().fields();
        assertEquals(3, fields.size());

        FormField passwordField = fields.get(0);
        assertEquals("ssh-password-node-123", passwordField.name());
        assertEquals("password", passwordField.type());
        assertEquals(FieldSource.SECRET, passwordField.source());
        assertFalse(passwordField.required());

        FormField keyField = fields.get(1);
        assertEquals("ssh-key-node-123", keyField.name());
        assertEquals("textarea", keyField.type());
        assertEquals(FieldSource.SECRET, keyField.source());
        assertFalse(keyField.required());

        FormField passphraseField = fields.get(2);
        assertEquals("ssh-passphrase-node-123", passphraseField.name());
        assertEquals("password", passphraseField.type());
        assertEquals(FieldSource.SECRET, passphraseField.source());
        assertFalse(passphraseField.required());

        assertTrue(ex.getFormSchema().actions().stream().anyMatch(a -> "ONCE".equals(a.name())));
        assertTrue(ex.getFormSchema().actions().stream().anyMatch(a -> "DENIED".equals(a.name())));
    }

    @Test
    void connectClient_requiresAuthenticatedPrincipalForCredentialLookup() throws Exception {
        SecureCredentialStore credentialStore = Mockito.mock(SecureCredentialStore.class);
                DatabaseRepository<VorkNode> nodeRepository = new MapDatabaseRepository<>(VorkNode.class);

        VirtualSshService service = new VirtualSshService();
        Field credentialStoreField = VirtualSshService.class.getDeclaredField("credentialStore");
        credentialStoreField.setAccessible(true);
        credentialStoreField.set(service, credentialStore);
        Field nodeRepositoryField = VirtualSshService.class.getDeclaredField("nodeRepository");
        nodeRepositoryField.setAccessible(true);
        nodeRepositoryField.set(service, nodeRepository);

        SecurityContextHolder.clearContext();

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.connectClient("system", "example.com", 1));

        assertTrue(ex.getMessage().contains("No authenticated principal"));
    }

    @Test
    void connectClient_withoutUsernameAndMultipleNodes_requestsUsernameSelection() throws Exception {
        SecureCredentialStore credentialStore = Mockito.mock(SecureCredentialStore.class);
        DatabaseRepository<VorkNode> nodeRepository = new MapDatabaseRepository<>(VorkNode.class);
        nodeRepository.save(new VorkNode("node-1", "alice", "example.com", "ubuntu", 1L, 1L, ""));
        nodeRepository.save(new VorkNode("node-2", "alice", "example.com", "ec2-user", 2L, 2L, ""));
        when(credentialStore.getSecret(any(VorkUser.class), any(String.class))).thenReturn(null);

        VirtualSshService service = new VirtualSshService();
        Field credentialStoreField = VirtualSshService.class.getDeclaredField("credentialStore");
        credentialStoreField.setAccessible(true);
        credentialStoreField.set(service, credentialStore);
        Field nodeRepositoryField = VirtualSshService.class.getDeclaredField("nodeRepository");
        nodeRepositoryField.setAccessible(true);
        nodeRepositoryField.set(service, nodeRepository);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        ToolSuspensionException ex = assertThrows(
                ToolSuspensionException.class,
                () -> service.connectClient(null, "example.com", 1));

        assertEquals("SSH Username Required", ex.getFormSchema().title());
        assertEquals(1, ex.getFormSchema().fields().size());
        FormField usernameField = ex.getFormSchema().fields().get(0);
        assertEquals("ssh-node-username-example_com", usernameField.name());
        assertEquals("text", usernameField.type());
        assertEquals(FieldSource.CONTEXT, usernameField.source());
        assertTrue(usernameField.required());
                assertTrue(usernameField.options().contains("ubuntu"));
                assertTrue(usernameField.options().contains("ec2-user"));
    }

        @Test
        void connectClient_withoutUsernameAndSingleNode_usesExistingNode() throws Exception {
                SecureCredentialStore credentialStore = Mockito.mock(SecureCredentialStore.class);
                DatabaseRepository<VorkNode> nodeRepository = new MapDatabaseRepository<>(VorkNode.class);
                nodeRepository.save(new VorkNode("node-777", "alice", "example.com", "ubuntu", 1L, 1L, ""));
                when(credentialStore.getSecret(any(VorkUser.class), any(String.class))).thenReturn(null);

                VirtualSshService service = new VirtualSshService();
                Field credentialStoreField = VirtualSshService.class.getDeclaredField("credentialStore");
                credentialStoreField.setAccessible(true);
                credentialStoreField.set(service, credentialStore);
                Field nodeRepositoryField = VirtualSshService.class.getDeclaredField("nodeRepository");
                nodeRepositoryField.setAccessible(true);
                nodeRepositoryField.set(service, nodeRepository);

                SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken("alice", "n/a"));

                assertThrows(ToolSuspensionException.class, () -> service.connectClient(null, "example.com", 1));

                verify(credentialStore).getSecret(eq(new VorkUser("alice", "", "USER", 0L, 0L)), eq("ssh-password-node-777"));
                verify(credentialStore).getSecret(eq(new VorkUser("alice", "", "USER", 0L, 0L)), eq("ssh-key-node-777"));
                verify(credentialStore).getSecret(eq(new VorkUser("alice", "", "USER", 0L, 0L)), eq("ssh-passphrase-node-777"));
        }
}
