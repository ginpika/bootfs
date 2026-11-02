package cc.ginpika.bootfs.core.io;

import java.io.IOException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaProcessSecurityTest {
    @Test @Timeout(5) void aSilentMediaProcessCannotHoldTheReaderPastItsDeadline() {
        long start = System.nanoTime();
        assertThrows(IOException.class, () -> MediaProcess.capture(new ProcessBuilder("/bin/sleep", "30"), 1, 1024));
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 4000);
    }
    @Test @Timeout(5) void unlimitedChildOutputCannotExhaustHeap() {
        assertThrows(IOException.class, () -> MediaProcess.capture(new ProcessBuilder("/usr/bin/yes", "bounded"), 3, 1024));
    }
}
