package dev.rex.demo.ecr.api.dto;

import jakarta.validation.constraints.NotBlank;
import org.apache.commons.lang3.StringUtils;

/**
 * Repo 스캔 요청 DTO
 * - @Valid(Bean Validation) + validate()(수동 검증) 둘 다 지원
 * - 컨트롤러에서 req.validate()를 호출하므로 반드시 validate() 제공
 */
public record EcrRepoScanRequest(
        @NotBlank(message = "region은 필수입니다.") String region,
        @NotBlank(message = "accountId는 필수입니다.") String accountId,
        @NotBlank(message = "accessKeyId는 필수입니다.") String accessKeyId,
        @NotBlank(message = "secretAccessKey는 필수입니다.") String secretAccessKey
) {
    public void validate() {
        if (StringUtils.isBlank(region)) throw new IllegalArgumentException("region은 필수입니다.");
        if (StringUtils.isBlank(accountId)) throw new IllegalArgumentException("accountId는 필수입니다.");
        if (StringUtils.isBlank(accessKeyId)) throw new IllegalArgumentException("accessKeyId는 필수입니다.");
        if (StringUtils.isBlank(secretAccessKey)) throw new IllegalArgumentException("secretAccessKey는 필수입니다.");
    }

    public EcrCredentials toCredentials() {
        return new EcrCredentials(accessKeyId, secretAccessKey, null);
    }
}
