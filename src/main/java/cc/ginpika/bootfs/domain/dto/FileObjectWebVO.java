package cc.ginpika.bootfs.domain.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

// Web View Object for admin UI
@Data
@Builder
public class FileObjectWebVO {
    private int pageNumber;
    private int pageSize;
    private int total;
    private List<FileObject> rows;
}
