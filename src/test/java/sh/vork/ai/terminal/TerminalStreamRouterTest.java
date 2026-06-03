package sh.vork.ai.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import org.mockito.MockMakers;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.sshtools.client.SshClient;

import sh.vork.ssh.VirtualSshService;

class TerminalStreamRouterTest {

    @Test
    void sanitizeForModel_removesAnsiAndControlCharacters() {
        String raw = "\u001B[31mhelp\u001B[0m\r\nmore\u0007text";
        assertEquals("help\nmoretext", TerminalStreamRouter.sanitizeForModel(raw));
    }

    @Test
    void sanitizeForModel_returnsPlaceholderWhenOnlyControlsRemain() {
        String raw = "\u001B[0m\u0007\r\n";
        assertEquals("[terminal output omitted: control characters only]",
                TerminalStreamRouter.sanitizeForModel(raw));
    }

    @Test
    void selectUiOutput_usesBufferedCommandOutputWhenStreamIsEmpty() {
        assertEquals("file-one\nfile-two\n",
                TerminalStreamRouter.selectUiOutput("ls -l", "file-one\nfile-two\n"));
    }

    @Test
    void normalizeUiOutput_stripsEchoedCommandPrefix() {
        assertEquals("Filesystem  Size Used Avail Use% Mounted on\n",
                TerminalStreamRouter.normalizeUiOutput("df -h",
                        "ls -ldf -h\nFilesystem  Size Used Avail Use% Mounted on\n"));
    }

    @Test
    void normalizeUiOutput_stripsWholeEchoLineWhenCommandIsRepeated() {
        assertEquals("total 456\n-rw-r--r--  1 lee  staff  123 May 17 10:00 pom.xml\n",
                TerminalStreamRouter.normalizeUiOutput("ls -l",
                        "$ ls -lls -l\ntotal 456\n-rw-r--r--  1 lee  staff  123 May 17 10:00 pom.xml\n"));
    }

    @Test
    void normalizeUiOutput_returnsEmptyWhenOnlyEchoedCommandRemains() {
        assertTrue(TerminalStreamRouter.normalizeUiOutput("df -h", "ls -ldf -h").isEmpty());
    }

    @Test
    void hasDisplayableContent_falseForAnsiOnlyChunks() {
        assertTrue(!TerminalStreamRouter.hasDisplayableContent("\u001B[0m\u001B[?2004h"));
    }

    @Test
    void hasDisplayableContent_trueForTextChunks() {
        assertTrue(TerminalStreamRouter.hasDisplayableContent("total 12\n"));
    }

    @Test
    void appendLimited_truncatesAtConfiguredMaximum() {
        StringBuilder sb = new StringBuilder();
        TerminalStreamRouter.appendLimited(sb, "abcdef", 4);
        TerminalStreamRouter.appendLimited(sb, "gh", 4);
        assertEquals("abcd", sb.toString());
    }

    @Test
    void createClient_remoteHostWithoutUser_delegatesToVirtualSshService() throws Exception {
        VirtualSshService virtualSshService = mock(VirtualSshService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        SshClient expectedClient = mock(SshClient.class);
        when(virtualSshService.connectClient(null, "example.com", 10)).thenReturn(expectedClient);

        TerminalStreamRouter router = new TerminalStreamRouter(virtualSshService, null);
        Method createClient = TerminalStreamRouter.class.getDeclaredMethod("createClient", String.class, int.class);
        createClient.setAccessible(true);

        Object actual = createClient.invoke(router, "example.com", 10);
        assertSame(expectedClient, actual);
        verify(virtualSshService).connectClient(null, "example.com", 10);
    }

    @Test
    void createClient_remoteHostWithUser_delegatesExplicitUsername() throws Exception {
        VirtualSshService virtualSshService = mock(VirtualSshService.class, withSettings().mockMaker(MockMakers.SUBCLASS));
        SshClient expectedClient = mock(SshClient.class);
        when(virtualSshService.connectClient("ubuntu", "example.com", 10)).thenReturn(expectedClient);

        TerminalStreamRouter router = new TerminalStreamRouter(virtualSshService, null);
        Method createClient = TerminalStreamRouter.class.getDeclaredMethod("createClient", String.class, int.class);
        createClient.setAccessible(true);

        Object actual = createClient.invoke(router, "ubuntu@example.com", 10);
        assertSame(expectedClient, actual);
        verify(virtualSshService).connectClient("ubuntu", "example.com", 10);
    }
}
