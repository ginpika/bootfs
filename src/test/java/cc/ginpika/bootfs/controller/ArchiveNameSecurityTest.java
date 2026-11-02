package cc.ginpika.bootfs.controller;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveNameSecurityTest {
    @Test void untrustedNamesCannotCreateTraversalEntriesInExportedArchives() {
        assertEquals("evil.txt", WebUIApiController.archiveName("../../evil.txt", "fallback"));
        assertEquals("evil.txt", WebUIApiController.archiveName("C:\\Users\\evil.txt", "fallback"));
        assertEquals("C_evil.txt", WebUIApiController.archiveName("C:evil.txt", "fallback"));
        assertEquals("fallback", WebUIApiController.archiveName("..", "fallback"));
        assertEquals("file_name.txt", WebUIApiController.archiveName("file\nname.txt", "fallback"));
        assertEquals("中文文件.txt", WebUIApiController.archiveName("中文文件.txt", "fallback"));
    }
}
