package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.domain.dto.FileObject;

/** Result of a durable resource publication or text-generation replacement. */
public record PublishedResource(FileObject file, String etag) {}
