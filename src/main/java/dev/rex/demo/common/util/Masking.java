package dev.rex.demo.common.util;

import org.apache.commons.lang3.StringUtils;

/**
 * 민감정보 마스킹 유틸
 * - 로그/응답에서 노출 최소화 목적
 */
public final class Masking {

    private Masking() {
    }

    public static String maskAccessKeyId(String accessKeyId) {
        String s = StringUtils.trimToNull(accessKeyId);
        if (s == null) return "(null)";
        if (s.length() <= 6) return "***";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 2);
    }

    public static String maskDigest(String digest) {
        String s = StringUtils.trimToNull(digest);
        if (s == null) return null;
        // sha256:abcd.... -> 앞 12자만
        if (s.length() <= 20) return s;
        return s.substring(0, 12) + "...";
    }
}
