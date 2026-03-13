package cc.ginpika.bootfs.dto;

import lombok.Data;

@Data
public class SsoUser {
    private String id;
    private String username;
    private String email;
    private String role;
}
