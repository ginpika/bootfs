package cc.ginpika.bootfs.kernel.metadata;

import org.rocksdb.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local state machine with synchronous WAL and atomically persisted application progress. */
public final class RocksMetadataStore implements MetadataStore {
    private static final String INTERNAL = "\u0000bootfs/";
    private static final String REVISION = INTERNAL + "revision";
    private static final String APPLIED_INDEX = INTERNAL + "appliedIndex";
    private static final String APPLIED_TERM = INTERNAL + "appliedTerm";
    private static final String RESULTS = INTERNAL + "request/";
    private static final int SNAPSHOT_MAGIC = 0x42465332;
    static { RocksDB.loadLibrary(); }

    // Some platforms scope native DB locks to the process; prevent a second in-process open too.
    private static final Set<Path> OPEN_DIRECTORIES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Path directory;
    private final Options options;
    private final WriteOptions writes;
    private final RocksDB db;
    private boolean closed;

    public RocksMetadataStore(Path directory) {
        try { Files.createDirectories(directory); this.directory = directory.toRealPath(); }
        catch (IOException failure) { throw new MetadataException("Cannot resolve metadata directory", failure); }
        if (!OPEN_DIRECTORIES.add(this.directory)) throw new MetadataException("Metadata directory is already open in this process: " + this.directory);
        Options openedOptions = new Options().setCreateIfMissing(true).setParanoidChecks(true);
        WriteOptions openedWrites = new WriteOptions().setSync(true).setDisableWAL(false);
        try {
            Files.createDirectories(directory);
            this.db = RocksDB.open(openedOptions, this.directory.toString());
            this.options = openedOptions;
            this.writes = openedWrites;
        } catch (IOException | RocksDBException | RuntimeException error) {
            OPEN_DIRECTORIES.remove(this.directory);
            openedWrites.close();
            openedOptions.close();
            throw new MetadataException("Cannot open metadata at " + directory, error);
        }
    }

    public static void validateKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.indexOf('\u0000') >= 0) throw new IllegalArgumentException("NUL is reserved in metadata keys");
    }

    private void ensureOpen() { if (closed) throw new MetadataException("Metadata store is closed"); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private String rawGet(String key) throws RocksDBException {
        byte[] value = db.get(bytes(key));
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }
    private long number(String key, long fallback) {
        try { String value = rawGet(key); return value == null ? fallback : Long.parseLong(value); }
        catch (RocksDBException error) { throw new MetadataException("Cannot read metadata progress", error); }
    }

    @Override public synchronized String get(String key) {
        validateKey(key); ensureOpen();
        try { return rawGet(key); }
        catch (RocksDBException error) { throw new MetadataException("Cannot read metadata key", error); }
    }
    @Override public synchronized void put(String key, String value) {
        writeBatch(Collections.singletonMap(key, Objects.requireNonNull(value, "value")), Set.of());
    }
    @Override public synchronized boolean delete(String key) {
        boolean existed = get(key) != null;
        writeBatch(Map.of(), Set.of(key));
        return existed;
    }
    @Override public synchronized NavigableMap<String, String> scan(String prefix) {
        validateKey(prefix); ensureOpen();
        NavigableMap<String, String> result = scanRaw(prefix);
        result.keySet().removeIf(key -> key.startsWith(INTERNAL));
        return result;
    }
    private NavigableMap<String, String> scanRaw(String prefix) {
        NavigableMap<String, String> result = new TreeMap<>();
        try (RocksIterator iterator = db.newIterator()) {
            iterator.seek(bytes(prefix));
            while (iterator.isValid()) {
                String key = new String(iterator.key(), StandardCharsets.UTF_8);
                if (!key.startsWith(prefix)) break;
                result.put(key, new String(iterator.value(), StandardCharsets.UTF_8));
                iterator.next();
            }
            iterator.status();
            return result;
        } catch (RocksDBException error) { throw new MetadataException("Cannot scan metadata", error); }
    }
    @Override public synchronized void deletePrefix(String prefix) {
        writeBatch(Map.of(), scan(prefix).keySet());
    }
    @Override public synchronized void writeBatch(Map<String, String> puts, Set<String> deletes) {
        apply(puts, deletes, -1, -1);
    }
    @Override public synchronized boolean compareAndSet(String key, String expected, String replacement) {
        if (!Objects.equals(get(key), expected)) return false;
        if (replacement == null) writeBatch(Map.of(), Set.of(key));
        else writeBatch(Map.of(key, replacement), Set.of());
        return true;
    }
    @Override public synchronized boolean compareAndWrite(Map<String,String> expected, Map<String,String> puts, Set<String> deletes) {
        for (Map.Entry<String,String> entry : expected.entrySet()) if (!Objects.equals(get(entry.getKey()), entry.getValue())) return false;
        writeBatch(puts, deletes); return true;
    }
    @Override public synchronized long revision() { ensureOpen(); return number(REVISION, 0); }
    public synchronized long appliedIndex() { ensureOpen(); return number(APPLIED_INDEX, -1); }
    public synchronized long appliedTerm() { ensureOpen(); return number(APPLIED_TERM, 0); }

    /** Called only by the replicated state machine; applies data and Raft progress in one WAL record. */
    public synchronized void apply(Map<String, String> puts, Set<String> deletes, long term, long index) {
        apply(puts, deletes, term, index, null, null);
    }
    public synchronized void apply(Map<String, String> puts, Set<String> deletes, long term, long index,
                                   String requestId, String response) {
        ensureOpen();
        puts.forEach((key, value) -> { validateKey(key); Objects.requireNonNull(value, "value"); });
        deletes.forEach(RocksMetadataStore::validateKey);
        if (index >= 0 && index <= appliedIndex()) return;
        try (WriteBatch batch = new WriteBatch()) {
            // A supplied put wins when a key occurs in both collections.
            for (String key : new TreeSet<>(deletes)) batch.delete(bytes(key));
            for (Map.Entry<String, String> entry : new TreeMap<>(puts).entrySet()) {
                batch.put(bytes(entry.getKey()), bytes(entry.getValue()));
            }
            batch.put(bytes(REVISION), bytes(Long.toString(revision() + 1)));
            if (index >= 0) {
                batch.put(bytes(APPLIED_INDEX), bytes(Long.toString(index)));
                batch.put(bytes(APPLIED_TERM), bytes(Long.toString(term)));
            }
            cacheResult(batch, requestId, response);
            db.write(writes, batch);
        } catch (RocksDBException error) { throw new MetadataException("Cannot persist metadata batch", error); }
    }

    /** Persist a no-op (e.g. failed conditional mutation) without incrementing the user revision. */
    public synchronized void advance(long term, long index) {
        advance(term, index, null, null);
    }
    public synchronized void advance(long term, long index, String requestId, String response) {
        ensureOpen();
        if (index <= appliedIndex()) return;
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(bytes(APPLIED_INDEX), bytes(Long.toString(index)));
            batch.put(bytes(APPLIED_TERM), bytes(Long.toString(term)));
            cacheResult(batch, requestId, response);
            db.write(writes, batch);
        } catch (RocksDBException error) { throw new MetadataException("Cannot persist Raft progress", error); }
    }

    public synchronized String requestResult(String requestId) {
        ensureOpen();
        try { return rawGet(resultKey(requestId)); }
        catch (RocksDBException error) { throw new MetadataException("Cannot read durable command result", error); }
    }
    private static String resultKey(String requestId) { return RESULTS + UUID.fromString(requestId); }
    private static void cacheResult(WriteBatch batch, String requestId, String response) throws RocksDBException {
        if (requestId != null) batch.put(bytes(resultKey(requestId)), bytes(Objects.requireNonNull(response, "response")));
    }

    /** Portable data-only snapshot. The caller synchronizes with apply and supplies Ratis framing. */
    public synchronized void exportSnapshot(Path target) throws IOException {
        ensureOpen();
        try (FileOutputStream file = new FileOutputStream(target.toFile());
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(file))) {
            out.writeInt(SNAPSHOT_MAGIC);
            out.writeLong(revision()); out.writeLong(appliedTerm()); out.writeLong(appliedIndex());
            NavigableMap<String, String> entries = scanRaw("");
            out.writeInt(entries.size());
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                writeString(out, entry.getKey()); writeString(out, entry.getValue());
            }
            out.flush(); file.getFD().sync();
        }
    }

    public synchronized void importSnapshot(Path source) throws IOException {
        ensureOpen();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(source)));
             WriteBatch batch = new WriteBatch()) {
            if (in.readInt() != SNAPSHOT_MAGIC) throw new IOException("Unknown metadata snapshot format");
            long revision = in.readLong(), term = in.readLong(), index = in.readLong();
            int count = in.readInt();
            if (revision < 0 || term < 0 || index < -1 || count < 0) throw new IOException("Invalid snapshot header");
            for (String key : scanRaw("").keySet()) batch.delete(bytes(key));
            for (int i = 0; i < count; i++) {
                String key = readString(in), value = readString(in);
                if (key.startsWith(INTERNAL)) {
                    if (!key.equals(REVISION) && !key.equals(APPLIED_TERM) && !key.equals(APPLIED_INDEX) && !key.startsWith(RESULTS)) {
                        throw new IOException("Unknown internal snapshot key");
                    }
                } else validateKey(key);
                batch.put(bytes(key), bytes(value));
            }
            if (in.read() != -1) throw new IOException("Unexpected trailing snapshot data");
            batch.put(bytes(REVISION), bytes(Long.toString(revision)));
            batch.put(bytes(APPLIED_TERM), bytes(Long.toString(term)));
            batch.put(bytes(APPLIED_INDEX), bytes(Long.toString(index)));
            db.write(writes, batch);
        } catch (RocksDBException | IllegalArgumentException error) {
            throw new IOException("Cannot install metadata snapshot", error);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] encoded = bytes(value); out.writeInt(encoded.length); out.write(encoded);
    }
    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) throw new IOException("Invalid snapshot value length");
        byte[] value = in.readNBytes(length);
        if (value.length != length) throw new EOFException("Truncated metadata snapshot");
        return new String(value, StandardCharsets.UTF_8);
    }
    @Override public synchronized void close() {
        if (!closed) { closed = true; try { db.close(); writes.close(); options.close(); } finally { OPEN_DIRECTORIES.remove(directory); } }
    }
}
