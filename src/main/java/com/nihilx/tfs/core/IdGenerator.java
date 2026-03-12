package com.nihilx.tfs.core;

import java.security.SecureRandom;

public class IdGenerator {
    private static final String CHAR_SET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom random = new SecureRandom();

    public static String generateRandomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = random.nextInt(CHAR_SET.length());
            sb.append(CHAR_SET.charAt(index));
        }
        return sb.toString();
    }

    // 模拟业务逻辑：生成并确保唯一
    public static String getUniqueId() {
        String id;

        while (true) {
            id = generateRandomId(16);
            // 这里的 checkExistsInDb 是你需要实现的数据库查询逻辑
            if (!checkExistsInDb(id)) {
                return id;
            }
        }
    }

    private static boolean checkExistsInDb(String id) {
        return false;
    }
}
