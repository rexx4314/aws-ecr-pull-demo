package dev.rex.demo.ecr.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.apache.commons.lang3.StringUtils;

public record EcrDockerSaveExportRequest(
        @NotBlank(message = "region은 필수입니다.")
        String region,

        @NotBlank(message = "accountId는 필수입니다.")
        String accountId,

        @NotBlank(message = "accessKeyId는 필수입니다.")
        String accessKeyId,

        @NotBlank(message = "secretAccessKey는 필수입니다.")
        String secretAccessKey,

        String sessionToken,

        @NotBlank(message = "repositoryName은 필수입니다.")
        String repositoryName,

        String tag,
        String digest,

        boolean resolveLatest,

        boolean includeConfig,
        boolean verifySha256,

        String outputDir,

        String repoTag,
        String fileNameHint,

        Integer httpTimeoutSeconds,
        Integer maxRetries,
        Integer maxPages,
        Integer maxImages
) {
    @AssertTrue(message = "resolveLatest=false이면 tag 또는 digest 중 하나만 제공해야 합니다.")
    public boolean isTagDigestValid() {
        if (resolveLatest) return true;
        boolean hasTag = StringUtils.isNotBlank(tag);
        boolean hasDigest = StringUtils.isNotBlank(digest);
        return hasTag ^ hasDigest;
    }
}
