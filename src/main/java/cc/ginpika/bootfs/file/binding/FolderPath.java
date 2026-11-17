package cc.ginpika.bootfs.file.binding;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

/** Path rules for the object namespace shown as folders in the browser. */
public final class FolderPath {
    private FolderPath() {}

    public static void requireDirectoryPrefix(String folder) {
        if (folder == null || (!folder.isEmpty() && !folder.endsWith("/"))) throw badRequest("Invalid folder path");
        requireKeyLengthAndCharacters(folder);
    }
    public static String uploadKey(String folder, String name) {
        requireDirectoryPrefix(folder);
        requireName(name);
        String key = folder + name;
        requireKeyLengthAndCharacters(key);
        return key;
    }
    private static void requireKeyLengthAndCharacters(String key) {
        if (key.getBytes(StandardCharsets.UTF_8).length > 1024 || key.chars().anyMatch(Character::isISOControl))
            throw badRequest("Invalid object path");
    }
    public static void requireFolder(String folder) {
        requireDirectoryPrefix(folder);
        if (folder.isEmpty() || folder.startsWith("/")) throw badRequest("Invalid folder path");
    }
    public static void requireObjectKey(String key) {
        if (key == null || key.isEmpty() || key.startsWith("/") || key.endsWith("/") || key.contains("\\"))
            throw badRequest("Invalid object path");
        requireKeyLengthAndCharacters(key);
        requireName(key.substring(key.lastIndexOf('/') + 1));
    }
    public static void requireName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 255
                || name.contains("/") || name.contains("\\") || name.chars().anyMatch(Character::isISOControl)) throw badRequest("Invalid folder name");
    }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
