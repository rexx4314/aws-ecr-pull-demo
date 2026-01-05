package dev.rex.demo.ecr.api.dto;

import jakarta.validation.constraints.NotBlank;
import org.apache.commons.lang3.StringUtils;

/**
 * ECR 리포지토리 스캔 요청 DTO
 * <p>
 * 컨트롤러에서 {@code validate()}를 호출하므로 수동 검증 메서드를 제공해야 함
 *
 * @param region          AWS 리전 (필수)
 * @param accountId       AWS 계정 ID (필수)
 * @param accessKeyId     액세스 키 ID (필수)
 * @param secretAccessKey 비밀 액세스 키 (필수)
 */
public record EcrRepoScanRequest(
        @NotBlank(message = "region은 필수입니다.") String region,
        @NotBlank(message = "accountId는 필수입니다.") String accountId,
        @NotBlank(message = "accessKeyId는 필수입니다.") String accessKeyId,
        @NotBlank(message = "secretAccessKey는 필수입니다.") String secretAccessKey
) {
    /**
     * 수동 검증을 수행
     * <p>
     * Bean Validation(@Valid) 외에 컨트롤러에서 명시적으로 호출되어
     * 각 필드가 비어있는지 확인하고, 비어있으면 {@link IllegalArgumentException} 발생
     */
    public void validate() {
        if (StringUtils.isBlank(region)) throw new IllegalArgumentException("region은 필수입니다.");
        if (StringUtils.isBlank(accountId)) throw new IllegalArgumentException("accountId는 필수입니다.");
        if (StringUtils.isBlank(accessKeyId)) throw new IllegalArgumentException("accessKeyId는 필수입니다.");
        if (StringUtils.isBlank(secretAccessKey)) throw new IllegalArgumentException("secretAccessKey는 필수입니다.");
    }
}
