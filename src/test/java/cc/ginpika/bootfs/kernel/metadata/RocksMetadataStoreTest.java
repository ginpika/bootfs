package cc.ginpika.bootfs.kernel.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class RocksMetadataStoreTest {
    @TempDir Path temporary;

    @Test void durableBatchRecoveryAndPrefixBoundaries() {
        Path directory = temporary.resolve("db");
        long revision;
        try (RocksMetadataStore store = new RocksMetadataStore(directory)) {
            store.writeBatch(Map.of("/files/a", "甲", "/files/ab", "乙", "/files-other/a", "outside"), Set.of());
            store.writeBatch(Map.of("/files/c", "丙"), Set.of("/files/a"));
            revision = store.revision();
            assertEquals(Map.of("/files/ab", "乙", "/files/c", "丙"), store.scan("/files/"));
        }
        try (RocksMetadataStore reopened = new RocksMetadataStore(directory)) {
            assertEquals(revision, reopened.revision());
            assertNull(reopened.get("/files/a"));
            assertEquals("丙", reopened.get("/files/c"));
            reopened.deletePrefix("/files/");
            assertEquals(Map.of("/files-other/a", "outside"), reopened.scan(""));
        }
    }

    @Test void invalidBatchDoesNotPartiallyApply() {
        try (RocksMetadataStore store = new RocksMetadataStore(temporary.resolve("db"))) {
            store.put("existing", "old");
            long before = store.revision();
            assertThrows(IllegalArgumentException.class,
                    () -> store.writeBatch(Map.of("new", "new", "\u0000reserved", "bad"), Set.of("existing")));
            assertEquals(before, store.revision());
            assertEquals(Map.of("existing", "old"), store.scan(""));
        }
    }

    @Test void compareAndSetAvoidsLostUpdates() throws Exception {
        try (RocksMetadataStore store = new RocksMetadataStore(temporary.resolve("db"))) {
            assertTrue(store.compareAndSet("counter", null, "0"));
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                List<Callable<Void>> jobs = new ArrayList<>();
                for (int worker = 0; worker < 4; worker++) jobs.add(() -> {
                    for (int i = 0; i < 25; i++) {
                        while (true) {
                            String before = store.get("counter");
                            if (store.compareAndSet("counter", before, Integer.toString(Integer.parseInt(before) + 1))) break;
                        }
                    }
                    return null;
                });
                for (Future<Void> result : workers.invokeAll(jobs)) result.get();
            } finally { workers.shutdownNow(); }
            assertEquals("100", store.get("counter"));
            assertFalse(store.compareAndSet("counter", "0", "wrong"));
            assertTrue(store.compareAndSet("counter", "100", null));
            assertNull(store.get("counter"));
        }
    }

    @Test void snapshotRestoresValuesAndAppliedProgressAtomically() throws Exception {
        Path snapshot = temporary.resolve("snapshot");
        String requestId = UUID.randomUUID().toString();
        String laterRequest = UUID.randomUUID().toString();
        try (RocksMetadataStore store = new RocksMetadataStore(temporary.resolve("db"))) {
            store.apply(Map.of("a", "one"), Set.of(), 2, 10, requestId, "cached-result");
            store.exportSnapshot(snapshot);
            store.apply(Map.of("a", "two", "b", "extra"), Set.of(), 3, 11, laterRequest, "later-result");
            store.importSnapshot(snapshot);
            assertEquals(Map.of("a", "one"), store.scan(""));
            assertEquals(2, store.appliedTerm()); assertEquals(10, store.appliedIndex());
            assertEquals(1, store.revision());
            assertEquals("cached-result", store.requestResult(requestId));
            assertNull(store.requestResult(laterRequest));
            store.apply(Map.of("a", "replay"), Set.of(), 2, 10);
            assertEquals("one", store.get("a"));
            byte[] truncated = Arrays.copyOf(Files.readAllBytes(snapshot), 15);
            Files.write(snapshot, truncated);
            assertThrows(java.io.IOException.class, () -> store.importSnapshot(snapshot));
            assertEquals(Map.of("a", "one"), store.scan(""));
        }
    }
}
