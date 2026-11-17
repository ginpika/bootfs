package cc.ginpika.bootfs.file.archive;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveNameSecurityTest {
    @Test void untrustedNamesCannotCreateTraversalEntriesInExportedArchives() {
        assertEquals("evil.txt", BatchArchiveService.archiveName("../../evil.txt", "fallback"));
        assertEquals("evil.txt", BatchArchiveService.archiveName("C:\\Users\\evil.txt", "fallback"));
        assertEquals("C_evil.txt", BatchArchiveService.archiveName("C:evil.txt", "fallback"));
        assertEquals("fallback", BatchArchiveService.archiveName("..", "fallback"));
        assertEquals("file_name.txt", BatchArchiveService.archiveName("file\nname.txt", "fallback"));
        assertEquals("中文文件.txt", BatchArchiveService.archiveName("中文文件.txt", "fallback"));
    }
}
