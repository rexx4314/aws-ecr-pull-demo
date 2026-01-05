package dev.rex.demo.ecr.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.apache.commons.lang3.StringUtils;

/**
 * ECR 이미지 다운로드 요청 DTO
 * <p>
 * 요청 검증과 다운로드 옵션을 포함
 *
 * @param region             AWS 리전
 * @param accountId          AWS 계정 ID
 * @param accessKeyId        액세스 키 ID
 * @param secretAccessKey    비밀 액세스 키
 * @param sessionToken       세션 토큰 (선택)
 * @param repositoryName     ECR 저장소 이름
 * @param tag                이미지 태그 (조건부)
 * @param digest             이미지 다이제스트 (조건부)
 * @param resolveLatest      true면 tag/digest 없이 최신 이미지 허용
 * @param includeConfig      이미지 config 포함 여부
 * @param verifySha256       sha256 검증 여부
 * @param outputDir          출력 디렉터리 경로 (선택)
 * @param concurrency        동시 처리 수 (선택, 1 이상)
 * @param maxRetries         최대 재시도 횟수 (선택, 1..20)
 * @param httpTimeoutSeconds HTTP 타임아웃 초 (선택, 1..3600)
 * @param maxPages           페이지당 최대 (선택, 1 이상)
 * @param maxImages          처리할 최대 이미지 수 (선택, 1 이상)
 */
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
     * - resolveLatest=false 인 경우 tag xor digest 여야 함
     * - resolveLatest=true  인 경우 tag/digest 없이도 유효함(서버가 최신 선택)
     *
     * @return 조건을 만족하면 true
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
