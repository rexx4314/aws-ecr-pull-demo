package dev.rex.demo.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * ECR 저장소 스캔 요청을 나타내는 DTO 레코드
 *
 * @param region          AWS 리전 (예: ap-northeast-2)
 * @param accountId       AWS 계정 ID (12자리 숫자)
 * @param accessKeyId     AWS 액세스 키 ID
 * @param secretAccessKey AWS 시크릿 액세스 키
 */
public record EcrRepoScanRequest(
        @NotBlank String region,
        @NotBlank @Pattern(regexp = "^[0-9]{12}$", message = "accountId는 12자리 숫자여야 합니다") String accountId,
        @NotBlank String accessKeyId,
        @NotBlank String secretAccessKey
) {
}
