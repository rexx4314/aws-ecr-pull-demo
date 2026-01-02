package dev.rex.demo.ecr.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.apache.commons.lang3.StringUtils;

public record EcrDownloadRequest(
        @NotBlank(message = "region은 필수입니다.")
        String region,

        @NotBlank(message = "accountId는 필수입니다.")
        String accountId,

        @NotBlank(message = "accessKeyId는 필수입니다.")
        String accessKeyId,

        @NotBlank(message = "secretAccessKey는 필수입니다.")
        String secretAccessKey,

        // 확장 가능한 자격 증명
        String sessionToken,

        @NotBlank(message = "repositoryName은 필수입니다.")
        String repositoryName,

        // tag 또는 digest 중 하나(조건부)
        String tag,
        String digest,

        boolean resolveLatest,
        boolean includeConfig,
        boolean verifySha256,
        String outputDir,

        @Min(value = 1, message = "concurrency는 1 이상이어야 합니다.")
        Integer concurrency,

        @Min(value = 1, message = "maxRetries는 1 이상이어야 합니다.")
        @Max(value = 20, message = "maxRetries는 20 이하여야 합니다.")
        Integer maxRetries,

        @Min(value = 1, message = "httpTimeoutSeconds는 1 이상이어야 합니다.")
        @Max(value = 3600, message = "httpTimeoutSeconds는 3600 이하여야 합니다.")
        Integer httpTimeoutSeconds,

        @Min(value = 1, message = "maxPages는 1 이상이어야 합니다.")
        Integer maxPages,

        @Min(value = 1, message = "maxImages는 1 이상이어야 합니다.")
        Integer maxImages
) {
    /**
     * 교차 검증:
     * - resolveLatest=false 인 경우 tag xor digest
     * - resolveLatest=true  인 경우 tag/digest 없어도 됨(서버가 최신 선택)
     */
    @AssertTrue(message = "resolveLatest=false이면 tag 또는 digest 중 하나만 제공해야 합니다.")
    @SuppressWarnings("unused")
    public boolean isTagDigestValid() {
        if (resolveLatest) return true;
        boolean hasTag = StringUtils.isNotBlank(tag);
        boolean hasDigest = StringUtils.isNotBlank(digest);
        return hasTag ^ hasDigest; // XOR
    }
}
