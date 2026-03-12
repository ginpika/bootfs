package com.nihilx.tfs.dto;

import lombok.Data;

@Data
public class SsoSession {
    private boolean authenticated;
    private SsoUser user;
}
