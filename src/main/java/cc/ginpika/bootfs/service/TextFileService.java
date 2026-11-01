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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/** Bounded, strict UTF-8 editing for explicitly recognized developer text files. */
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

    public record TextDocument(String uuid, String fileName, String content, String version, long size, String url) {}

    public TextDocument read(String uuid) throws IOException {
        FileObject file = context.query(uuid);
        if (file == null || files.isDeleted(file) || file.getPath() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在或已删除");
        }
        String name = file.getFileName();
        String documentRaw = metadata.getOne("/documents/full-text/" + uuid);
        if (documentRaw != null) {
            JSONObject document = JSONObject.parseObject(documentRaw);
            if (document.getString("fileName") != null) name = document.getString("fileName");
            else if (document.getString("title") != null) name = document.getString("title");
        }
        if (!editableName(name)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "此文件类型不支持文本编辑");
        Path path = Path.of(file.getPath());
        if (!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件内容不存在");
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文本编辑器最多支持 2 MiB 的文件");
        String content;
        try { content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException invalid) { throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "只支持 UTF-8 编码的文本文件"); }
        validateCharacters(content);
        String version = ContentHash.sha256(new ByteArrayInputStream(bytes));
        return new TextDocument(uuid, name, content, version, bytes.length, context.buildUrl(uuid));
    }

    public TextDocument save(String uuid, String content, String version) throws IOException {
        if (content == null || version == null || !version.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少文本内容或有效的文件版本");
        }
        if (content.length() > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文本编辑器最多支持 2 MiB 的文件");
        validateCharacters(content);
        byte[] bytes;
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(content));
            bytes = new byte[encoded.remaining()]; encoded.get(bytes);
        } catch (CharacterCodingException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文本包含无效的 Unicode 字符"); }
        if (bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文本编辑器最多支持 2 MiB 的文件");
        // Validate the existing bytes as well: a misleading text extension must never permit binary editing.
        FileObject current = context.query(uuid);
        if (current != null && !files.isDeleted(current)) read(uuid);
        FileService.PublishedResource published = files.replaceText(uuid, version, bytes);
        return read(published.file().getUuid());
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
