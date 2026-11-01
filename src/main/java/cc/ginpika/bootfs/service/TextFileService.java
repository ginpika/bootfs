package cc.ginpika.bootfs.service;

import cc.ginpika.bootfs.core.Context;
import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.kernel.object.ContentHash;
import cc.ginpika.bootfs.service.etcd.EtcdService;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Bounded, lossless UTF-8/GBK editing for explicitly recognized developer text files. */
@Service
public class TextFileService {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of(
            "yaml", "yml", "toml", "ini", "conf", "cfg", "properties", "env", "json", "jsonc", "xml",
            "txt", "text", "md", "markdown", "log", "csv", "tsv", "rst", "js", "mjs", "cjs", "ts", "jsx", "tsx",
            "css", "scss", "less", "html", "htm", "java", "py", "sh", "bash", "zsh", "sql", "c", "h", "cpp",
            "hpp", "go", "rs", "rb", "php", "kt", "kts", "swift", "vue", "svelte");
    private static final Set<String> NAMES = Set.of("dockerfile", "makefile", ".gitignore", ".gitattributes",
            ".editorconfig", ".npmrc", ".yarnrc", ".bashrc", ".zshrc");
    private final Context context;
    private final FileService files;
    private final EtcdService metadata;

    public TextFileService(Context context, FileService files, EtcdService metadata) {
        this.context = context; this.files = files; this.metadata = metadata;
    }

    public record TextDocument(String uuid, String fileName, String content, String version, long size, String url, String encoding, String detectedEncoding) {
        public TextDocument(String uuid, String fileName, String content, String version, long size, String url) {
            this(uuid, fileName, content, version, size, url, TextEncoding.UTF8, TextEncoding.UTF8);
        }
        public TextDocument(String uuid, String fileName, String content, String version, long size, String url, String encoding) {
            this(uuid, fileName, content, version, size, url, encoding, encoding);
        }
    }

    public TextDocument read(String uuid) throws IOException { return read(uuid, null); }

    public TextDocument read(String uuid, String encoding) throws IOException {
        // The content path and encoding metadata are switched atomically in the catalog.
        // Retry a read that straddles that switch rather than decoding old bytes with a new encoding.
        for (int attempt = 0; attempt < 4; attempt++) {
            try { return readSnapshot(uuid, encoding); }
            catch (ContentChangedException changed) { }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "文件正在更新，请稍后重新读取");
    }

    private TextDocument readSnapshot(String uuid, String encoding) throws IOException {
        TextEncoding.requested(encoding);
        FileObject file = context.query(uuid);
        if (file == null || files.isDeleted(file) || file.getPath() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在或已删除");
        }
        String name = file.getFileName();
        String encodingHint = null;
        String documentRaw = metadata.getOne("/documents/full-text/" + uuid);
        if (documentRaw != null) {
            JSONObject document = JSONObject.parseObject(documentRaw);
            encodingHint = TextEncoding.hint(document.getString("textEncoding"));
            if (document.getString("fileName") != null) name = document.getString("fileName");
            else if (document.getString("title") != null) name = document.getString("title");
        }
        String metaRaw = metadata.getOne("/files/" + uuid + "/meta");
        JSONObject fileMeta = metaRaw == null ? null : JSONObject.parseObject(metaRaw);
        String actualContentId = file.getContentObjectId() == null ? uuid : file.getContentObjectId();
        String expectedContentId = fileMeta == null ? actualContentId : fileMeta.getString("contentObjectId");
        if (expectedContentId == null) expectedContentId = actualContentId;
        if (!actualContentId.equals(expectedContentId)) {
            context.reload();
            throw new ContentChangedException();
        }
        if (encodingHint == null && fileMeta != null) encodingHint = TextEncoding.hint(fileMeta.getString("textEncoding"));
        if (!editableName(name)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "此文件类型不支持文本编辑");
        Path path = Path.of(file.getPath());
        if (!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件内容不存在");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文本编辑器最多支持 2 MiB 的文件");
        if (!java.util.Objects.equals(metaRaw, metadata.getOne("/files/" + uuid + "/meta"))
                || !java.util.Objects.equals(documentRaw, metadata.getOne("/documents/full-text/" + uuid))) throw new ContentChangedException();
        TextEncoding.Decoded detected = TextEncoding.decode(bytes, null, encodingHint);
        TextEncoding.Decoded decoded = TextEncoding.requested(encoding) == null ? detected : TextEncoding.decode(bytes, encoding, encodingHint);
        validateCharacters(decoded.content());
        String version = fileMeta == null ? null : fileMeta.getString("textVersion");
        if (version == null) version = ContentHash.sha256(new ByteArrayInputStream(bytes));
        return new TextDocument(uuid, name, decoded.content(), version, bytes.length, context.buildUrl(uuid), decoded.encoding(), detected.encoding());
    }

    private static final class ContentChangedException extends IOException {}

    public TextDocument save(String uuid, String content, String version) throws IOException { return save(uuid, content, version, null); }

    public TextDocument save(String uuid, String content, String version, String encoding) throws IOException {
        if (content == null || version == null || !version.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少文本内容或有效的文件版本");
        }
        if (content.length() > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文本编辑器最多支持 2 MiB 的文件");
        validateCharacters(content);
        String selected = TextEncoding.requested(encoding);
        String sourceEncoding = null;
        // Validate the existing bytes as well: a misleading text extension must never permit binary editing.
        FileObject current = context.query(uuid);
        if (current != null && !files.isDeleted(current)) {
            try { sourceEncoding = read(uuid).encoding(); }
            catch (ResponseStatusException failure) {
                // Some valid GBK bytes also decode as UTF-8 control characters. A user can
                // explicitly correct that interpretation; still reject binary/invalid text.
                if (selected == null || failure.getRawStatusCode() != 415) throw failure;
                sourceEncoding = read(uuid, selected).detectedEncoding();
            }
        }
        else {
            // Compatibility with save receipts produced before stable resource UUIDs were introduced.
            String receiptRaw = metadata.getOne("/text-replacements/" + uuid + "/" + version);
            if (receiptRaw != null) sourceEncoding = TextEncoding.hint(JSONObject.parseObject(receiptRaw).getString("textEncoding"));
        }
        if (selected == null) selected = sourceEncoding == null ? TextEncoding.UTF8 : sourceEncoding;
        byte[] bytes = TextEncoding.encode(content, selected);
        if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文本编辑器最多支持 2 MiB 的文件");
        FileService.PublishedResource published = files.replaceText(uuid, version, bytes, selected,
                sourceEncoding != null && !selected.equals(sourceEncoding));
        if (!uuid.equals(published.file().getUuid())) {
            throw new cc.ginpika.bootfs.s3.S3ConcurrentWriteException("此旧文件标识已失效，请刷新列表后打开当前文件");
        }
        TextDocument saved = read(uuid);
        if (!content.equals(saved.content()) || !selected.equals(saved.encoding())) {
            throw new cc.ginpika.bootfs.s3.S3ConcurrentWriteException("文件保存后又被更新，请重新加载最新内容");
        }
        return saved;
    }

    public boolean hasEditHistory(String uuid) { return !metadata.scan("/text-replacements/" + uuid + "/").isEmpty(); }

    public static boolean editableName(String filename) {
        if (filename == null) return false;
        String name = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        if (NAMES.contains(name) || name.equals(".env") || name.startsWith(".env.")) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static void validateCharacters(String content) {
        if (content.chars().anyMatch(character -> Character.isISOControl(character)
                && character != '\t' && character != '\n' && character != '\r' && character != '\f')) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件包含二进制或控制字符，无法作为文本编辑");
        }
    }
}
