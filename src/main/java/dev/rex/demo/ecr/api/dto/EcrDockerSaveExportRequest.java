package dev.rex.demo.ecr.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.apache.commons.lang3.StringUtils;

/**
 * ECR 도커 저장(export) 요청 DTO
 * <p>
 *
 * @param region             AWS 리전
 * @param accountId          AWS 계정 ID
 * @param accessKeyId        액세스 키 ID
 * @param secretAccessKey    비밀 액세스 키
 * @param sessionToken       세션 토큰 (선택)
 * @param repositoryName     저장소 이름
 * @param tag                이미지 태그 (선택)
 * @param digest             이미지 다이제스트 (선택)
 * @param resolveLatest      true면 최신 이미지 해석을 허용
 * @param includeConfig      이미지 config 포함 여부
 * @param verifySha256       sha256 검증 여부
 * @param outputDir          출력 디렉터리 경로 (선택)
 * @param repoTag            저장소 태그 (선택)
 * @param fileNameHint       파일명 힌트 (선택)
 * @param httpTimeoutSeconds HTTP 타임아웃 초 (선택)
 * @param maxRetries         최대 재시도 횟수 (선택)
 * @param maxPages           페이지당 최대 (선택)
 * @param maxImages          처리할 최대 이미지 수 (선택)
 */
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
    /**
     * tag/digest 조합의 유효성을 검증
     * <p>
     * resolveLatest가 true면 항상 유효(true)로 처리
     * resolveLatest가 false면 tag 또는 digest 중 정확히 하나가 제공되어야 함
     *
     * @return 유효하면 true, 그렇지 않으면 false
     */
    @AssertTrue(message = "resolveLatest=false이면 tag 또는 digest 중 하나만 제공해야 합니다.")
    @SuppressWarnings("unused")
    public boolean isTagDigestValid() {
        if (resolveLatest) return true;
        boolean hasTag = StringUtils.isNotBlank(tag);
        boolean hasDigest = StringUtils.isNotBlank(digest);
        return hasTag ^ hasDigest;
    }
}
