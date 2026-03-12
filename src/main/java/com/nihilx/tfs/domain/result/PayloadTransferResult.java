package com.nihilx.tfs.domain.result;

import com.nihilx.tfs.domain.dto.FileObject;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@EqualsAndHashCode(callSuper = true)
@Data
public class PayloadTransferResult<T extends Map<?, ?>> extends SimpleTransferResult {
    private T payload;

    PayloadTransferResult(Boolean succeed, String message, FileObject fileNode) {
        super(succeed, message, fileNode);
    }
}
