package com.nihilx.tfs.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// This object is planed to describe a tfs node of cluster
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NodeObject {
    private String url;
}
