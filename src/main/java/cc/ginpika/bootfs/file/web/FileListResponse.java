package cc.ginpika.bootfs.file.web;

import cc.ginpika.bootfs.core.ResourceUrls;

import cc.ginpika.bootfs.domain.dto.FileObject;
import cc.ginpika.bootfs.domain.dto.Tag;

import java.util.List;
import java.util.Map;

record FileListResponse(int pageNumber, int pageSize, int total, List<Row> rows,
                        boolean partial, List<String> unavailableNodes) {
    record Row(String path, String uuid, String contentObjectId, String fileName, Long size, String copyOf,
               String hlsAvailable, String thumbAvailable, String url, String thumbUrl, String parent,
               String albumAvailable, String nsfw, String isPublicAccess, List<Tag> tags, Long createdAt) {
        static Row fromInventory(FileObject file, String url, String thumbnail) {
            return new Row(file.getPath(), file.getUuid(), file.getContentObjectId(), file.getFileName(),
                    file.getSize(), file.getCopyOf(), file.getHlsAvailable(), file.getThumbAvailable(), url,
                    thumbnail, file.getParent(), file.getAlbumAvailable(), file.getNsfw(),
                    file.getIsPublicAccess(), file.getTags(), file.getCreatedAt());
        }

        static Row fromSearchHit(Map<?, ?> hit, String url, String endpoint) {
            List<Tag> tags = hit.get("tags") instanceof List<?> values
                    ? values.stream().map(String::valueOf).filter(value -> !value.isEmpty()).map(Tag::fromIndexFormat).toList()
                    : null;
            String id = (String) hit.get("uuid");
            String thumbnail = ResourceUrls.relative(textOrFallback(hit, "thumbUrl", "poster"),
                    "1".equals(hit.get("albumAvailable")) ? null : id);
            if (thumbnail != null && thumbnail.startsWith("/") && !thumbnail.startsWith("//")) {
                thumbnail = endpoint + thumbnail;
            }
            return new Row(null, id, null, textOrFallback(hit, "fileName", "title"),
                    longValue(hit.get("size")), (String) hit.get("copyOf"), (String) hit.get("hlsAvailable"),
                    (String) hit.get("thumbAvailable"), url, thumbnail,
                    (String) hit.get("parent"), (String) hit.get("albumAvailable"), (String) hit.get("nsfw"),
                    (String) hit.get("isPublicAccess"), tags, longValue(hit.get("fileCreatedAt")));
        }

        private static String textOrFallback(Map<?, ?> hit, String field, String fallback) {
            String value = (String) hit.get(field);
            return value == null ? (String) hit.get(fallback) : value;
        }

        private static Long longValue(Object value) {
            return value == null ? null : ((Number) value).longValue();
        }
    }
}
