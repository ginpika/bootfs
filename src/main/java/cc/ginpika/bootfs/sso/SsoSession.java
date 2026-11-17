package cc.ginpika.bootfs.sso;

import lombok.Data;

@Data
public class SsoSession {
    private boolean authenticated;
    private SsoUser user;
}
