package cc.ginpika.bootfs.kernel.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusterCredentialsTest {
    @TempDir Path root;
    @Test void generatedCredentialsPersistAndPairingCodesAreBoundToOneRequest() throws Exception {
        Path file = root.resolve("security/cluster.json");
        ClusterCredentials credentials = new ClusterCredentials(file, "", Clock.systemUTC());
        assertTrue(credentials.token().length() >= 40);
        String code = credentials.issueCode().getString("code");
        assertTrue(code.matches("[0-9]{8}"));
        String request = "a".repeat(64);
        String grant=credentials.redeem(code, request);
        assertNotEquals(credentials.token(),grant);
        assertEquals(grant, credentials.redeem(code, request), "The same request may recover a lost response");
        assertThrows(IOException.class, () -> credentials.redeem(code, "b".repeat(64)));
        ClusterCredentials restarted = new ClusterCredentials(file, "", Clock.systemUTC());
        assertEquals(credentials.token(), restarted.token());
        assertThrows(IOException.class, () -> restarted.redeem(code, request));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        assertEquals(credentials.publicKey(),restarted.publicKey());
        assertTrue(ClusterCredentials.verify(credentials.publicKey(),"node proof",credentials.sign("node proof")));
        assertFalse(ClusterCredentials.verify(credentials.publicKey(),"modified proof",credentials.sign("node proof")));
    }
    @Test void codesExpireAndFiveIncorrectAttemptsLockTheInvitation() throws Exception {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000L);
        ClusterCredentials credentials = new ClusterCredentials(root.resolve("credential.json"), "", clock);
        String request = "a".repeat(64);
        String code = credentials.issueCode().getString("code");
        when(clock.millis()).thenReturn(301000L);
        assertThrows(IOException.class, () -> credentials.redeem(code, request));
        String fresh = credentials.issueCode().getString("code");
        String wrong = fresh.equals("00000000") ? "00000001" : "00000000";
        for (int i=0; i<5; i++) assertThrows(IOException.class, () -> credentials.redeem(wrong, request));
        assertThrows(IOException.class, () -> credentials.redeem(fresh, request));
        String renewed = credentials.issueCode().getString("code");
        assertNotEquals(credentials.token(), credentials.redeem(renewed, request));
    }
}
