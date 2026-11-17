package cc.ginpika.bootfs.file;

import cc.ginpika.bootfs.file.binding.FolderPath;
import cc.ginpika.bootfs.file.inventory.LocalFileInventory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/** Shared resource intake and read facade for browser uploads, albums, and the S3 protocol adapter. */
@Service
@RequiredArgsConstructor
public class FileService {
    private final LocalFileInventory context;
    private final ObjectWritePolicy writePolicy;
    private final ResourcePublicationService publicationService;
    private final AlbumPublicationService albumService;

    public String upload(MultipartFile file) throws IOException {
        return upload(file, null);
    }

    public String upload(MultipartFile file, String requestId) throws IOException {
        String name = ResourcePublicationService.filename(file.getOriginalFilename());
        if ((name.startsWith("[Comic]") || name.startsWith("[Picture]"))
                && ResourcePublicationService.extension(name).equals(".zip")) {
            return albumService.uploadAlbum(file, requestId, null);
        }
        try (InputStream input = file.getInputStream()) {
            return context.buildUrl(publicationService.publishWithIntent(
                    input, name, publicationService.uploadId(requestId, "ordinary"), null, false, true, null, null).file().getUuid());
        }
    }

    public String uploadComic(MultipartFile file) throws IOException {
        return uploadComic(file, null);
    }

    public String uploadComic(MultipartFile file, String requestId) throws IOException {
        return uploadComic(file, requestId, "");
    }

    public String uploadComic(MultipartFile file, String requestId, String folder) throws IOException {
        String name = file.getOriginalFilename();
        String key = folder.isEmpty() ? null : FolderPath.uploadKey(folder, name);
        if (ResourcePublicationService.extension(ResourcePublicationService.filename(name)).equals(".zip")) {
            return albumService.uploadAlbum(file, requestId, key);
        }
        if (key == null) return upload(file, requestId);
        try (InputStream input = file.getInputStream()) {
            return context.buildUrl(publishBoundObject(input, key, writePolicy.bucket(), uploadContentType(file), null, null, requestId)
                    .file().getUuid());
        }
    }

    public PublishedResource publish(InputStream input, String originalFilename, boolean defaultS3Binding) throws IOException {
        return publicationService.publish(input, originalFilename, defaultS3Binding);
    }

    public PublishedResource publishBoundObject(InputStream input, String key, String bucket, String contentType,
                                                String expectedBinding, String payloadHash) throws IOException {
        return publicationService.publishBoundObject(input, key, bucket, contentType, expectedBinding, payloadHash);
    }

    public PublishedResource publishBoundObject(InputStream input, String key, String bucket, String contentType,
                                                String expectedBinding, String payloadHash, String requestId) throws IOException {
        return publicationService.publishBoundObject(input, key, bucket, contentType, expectedBinding, payloadHash, requestId);
    }

    public String resolveUploadId(String uuid) {
        return publicationService.resolveUploadId(uuid);
    }

    public String publicationState(String uuid) {
        return publicationService.publicationState(uuid);
    }

    public FileDownload openDownload(String uuid) throws IOException {
        return publicationService.openDownload(uuid);
    }

    private static String uploadContentType(MultipartFile file) {
        return file.getContentType() == null ? "application/octet-stream" : file.getContentType();
    }
}
