package com.nihilx.tfs.domain.result;

import com.nihilx.tfs.domain.dto.FileObject;
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