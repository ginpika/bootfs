package cc.ginpika.bootfs.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileObject {
    private String path;
    private String uuid;
    private String fileName;
    private Long size;
    // means this object is a copy
    private String copyOf;
    // means hls resource is ready
    private String hlsAvailable;
    // means webp thumbnail is ready
    private String thumbAvailable;
    // support download or direct read
    private transient String url;
    // means this object is a child-object
    private String parent;
    // means this zip archive is a comic or album, it`s ready for album route
    private String albumAvailable;
    // means this resource is not safe for work, maybe a porn or something else
    private String nsfw;
    // means this resource is a public resource, always describe for resource of image-hosting
    // support for random-picture
    private String isPublicAccess;
    // tags for classification and search
    private List<Tag> tags;
    // upload timestamp in milliseconds
    private Long createdAt;

    public FileObject(String path, String uuid, String fileName) {
        this.path = path;
        this.uuid = uuid;
        this.fileName = fileName;
    }

    public FileObject(String path, String uuid, String fileName, Long size) {
        this.path = path;
        this.uuid = uuid;
        this.fileName = fileName;
        this.size = size;
    }
}
