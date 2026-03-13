package cc.ginpika.bootfs.domain.result;

import cc.ginpika.bootfs.domain.dto.FileObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleTransferResult implements TransferResult {
    Boolean succeed = false;
    String message;
    FileObject fileNode;
}