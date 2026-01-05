package dev.rex.demo.ecr.model;

/**
 * ECR 로그인 토큰 정보를 담는 DTO 레코드
 *
 * @param registry ECR 레지스트리 URL (예: 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com)
 * @param username ECR 인증 사용자명 (보통 "AWS")
 * @param password ECR 인증 토큰 (Base64 디코딩된 비밀번호)
 */
public record EcrLoginToken(
        String registry,
        String username,
        String password
) {
}
