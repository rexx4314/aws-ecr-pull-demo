package dev.rex.demo.common.util;

import org.apache.commons.lang3.StringUtils;

/**
 * 민감정보 마스킹 유틸
 * <p>
 * - 로그 또는 응답에 민감한 값을 그대로 노출하지 않기 위한 간단한 마스킹 로직 제공
 */
public final class Masking {

    /**
     * 인스턴스화 금지
     */
    private Masking() {
    }

    /**
     * AWS Access Key ID를 간단히 마스킹
     * <p>
     * 동작:
     * - null 또는 빈 문자열일 경우 "(null)" 반환
     * - 길이 6 이하인 경우 보안을 위해 "***" 반환
     * - 그 외에는 앞 4자, 뒤 2자는 노출하고 중간은 "****"로 대체
     *
     * @param accessKeyId 원본 Access Key ID
     * @return 마스킹된 문자열 (null 입력 시 "(null)")
     */
    public static String maskAccessKeyId(String accessKeyId) {
        String s = StringUtils.trimToNull(accessKeyId);
        if (s == null) return "(null)";
        if (s.length() <= 6) return "***";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 2);
    }

    /**
     * 다이제스트(예: sha256:...)를 간단히 마스킹/단축
     * <p>
     * 동작:
     * - null 또는 빈 문자열이면 null 반환
     * - 길이가 20 이하이면 그대로 반환
     * - 그 외에는 앞 12자만 남기고 "..."을 붙여 반환
     *
     * @param digest 원본 다이제스트 문자열
     * @return 마스킹/단축된 다이제스트 또는 공백 문자열 (null 또는 빈 문자열 입력 시)
     */
    public static String maskDigest(String digest) {
        String s = StringUtils.trimToNull(digest);
        if (s == null) return "";
        // sha256:abcd.... -> 앞 12자만
        if (s.length() <= 20) return s;
        return s.substring(0, 12) + "...";
    }
}
