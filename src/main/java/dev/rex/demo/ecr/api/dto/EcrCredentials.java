package dev.rex.demo.ecr.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * AWS 자격 증명 DTO
 * <p>
 * - accessKeyId와 secretAccessKey는 필수
 * - sessionToken은 선택 항목(STS 사용 시 제공)
 * <p>
 * 검증은 Bean Validation(@Valid)으로 수행
 *
 * @param accessKeyId     AWS 액세스 키 ID (필수)
 * @param secretAccessKey AWS 비밀 액세스 키 (필수)
 * @param sessionToken    세션 토큰 (선택, STS 등)
 */
public record EcrCredentials(
        @NotBlank String accessKeyId,
        @NotBlank String secretAccessKey,
        String sessionToken
) {
}
