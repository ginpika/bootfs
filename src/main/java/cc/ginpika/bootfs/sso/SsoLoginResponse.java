package cc.ginpika.bootfs.sso;

import lombok.Data;

@Data
public class SsoLoginResponse {
    private boolean success;
    private SsoUser user;
}
