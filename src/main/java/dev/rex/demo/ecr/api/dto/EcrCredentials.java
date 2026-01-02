package dev.rex.demo.ecr.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * AWS 자격 증명 DTO
 * - accessKeyId/secretAccessKey 필수
 * - sessionToken 선택(STS 등)
 * <p>
 * 검증은 Bean Validation(@Valid)로만 수행한다.
 */
public record EcrCredentials(
        @NotBlank String accessKeyId,
        @NotBlank String secretAccessKey,
        String sessionToken
) {
}
