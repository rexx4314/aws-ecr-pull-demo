package dev.rex.demo.api.dto;

/**
 * ECR 이미지 풀 응답을 나타내는 DTO 레코드
 *
 * @param imageRef    풀된 이미지의 전체 참조값
 * @param resolvedTag 실제 적용된 이미지 태그
 * @param digest      이미지 다이제스트 (SHA256 해시)
 * @param message     풀 결과 메시지
 */
public record EcrPullResponse(
        String imageRef,
        String resolvedTag,
        String digest,
        String message
) {
}
