package cc.ginpika.bootfs.file;

import java.nio.file.Path;

/** Verified local file handle and HTTP metadata for resource downloads and previews. */
public record FileDownload(Path path, String filename, long length, String etag, boolean revalidate) {}
