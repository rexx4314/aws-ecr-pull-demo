package dev.rex.demo.infra.tar;

import org.apache.commons.lang3.StringUtils;

/**
 * Docker save 관련 파일명 처리 유틸리티 클래스
 * <p>
 * - sha256 접두사 제거 지원
 * - 사용자 입력 힌트로부터 안전한 .tar 파일명 생성
 */
public final class DockerSaveNames {

    /**
     * 인스턴스화 방지를 위한 private 생성자.
     */
    private DockerSaveNames() {
    }

    /**
     * 다이제스트 문자열에서 "sha256:" 접두사를 제거
     * <p>
     * - 입력을 trim 후 null이면 null을 반환
     * - "sha256:"로 시작하면 해당 접두사만 제거하고 반환
     * - 그 외에는 trim된 원본 문자열을 반환
     *
     * @param digest 이미지 다이제스트 (nullable)
     * @return 접두사가 제거된 다이제스트 또는 null
     */
    public static String stripSha256Prefix(String digest) {
        String d = StringUtils.trimToNull(digest);
        if (d == null) return null;
        return d.startsWith("sha256:") ? d.substring("sha256:".length()) : d;
    }

    /**
     * 사용자 힌트로부터 안전한 tar 파일명을 생성
     * <p>
     * - null/빈 힌트이면 기본값 "docker-save.tar"을 반환
     * - 경로 구분자(\\, /), 부모 디렉터리 참조(..), 콜론(:) 등을 '_'로 대체
     * - 소문자 비교로 .tar 확장자가 없으면 ".tar"을 추가
     *
     * @param hint 파일명 힌트 (nullable)
     * @return 안전한 tar 파일명
     */
    public static String safeTarFileName(String hint) {
        String s = StringUtils.trimToNull(hint);
        if (s == null) return "docker-save.tar";

        s = s.replace("\\", "_").replace("/", "_");
        s = s.replace("..", "_");
        s = s.replace(":", "_");

        if (!s.toLowerCase().endsWith(".tar")) s = s + ".tar";
        return s;
    }
}
