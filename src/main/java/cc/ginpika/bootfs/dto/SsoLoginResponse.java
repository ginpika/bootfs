package cc.ginpika.bootfs.dto;

import lombok.Data;

@Data
public class SsoLoginResponse {
    private boolean success;
    private SsoUser user;
}
