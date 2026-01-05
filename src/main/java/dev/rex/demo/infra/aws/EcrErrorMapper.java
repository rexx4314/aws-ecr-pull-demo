package dev.rex.demo.infra.aws;

import dev.rex.demo.common.error.ErrorCode;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.services.ecr.model.EcrException;

/**
 * ECR SDK 예외(EcrException)를 서비스 정책(ErrorCode)으로 매핑하는 공통 유틸
 *
 * <p>
 * 목적:
 * - 여러 컴포넌트(ManifestFetcher, BlobDownloader 등)에서 중복되는
 *   statusCode/awsErrorCode 기반 분류 로직을 한 곳으로 모아 일관성 유지
 *
 * <p>
 * 설계 포인트:
 * - "공통 분류(Unauthorized/Throttling)"는 여기서 100% 처리
 * - API별로 달라지는 NotFound 케이스(RepositoryNotFound, ImageNotFound, LayerNotFound 등)는
 *   호출 측에서 필요하면 추가로 보완할 수 있게 "기본값"을 제공
 */
public final class EcrErrorMapper {

    private EcrErrorMapper() {
        // util class
    }

    /**
     * EcrException을 ErrorCode로 매핑
     *
     * <p>
     * 공통 분류:
     * - 401/403 -> DOWNLOAD_UNAUTHORIZED
     * - 429 or Throttling* -> DOWNLOAD_ECR_THROTTLED
     *
     * <p>
     * 그 외는 호출 측 맥락에 따라 다를 수 있으므로 기본값을 DOWNLOAD_ECR_API_FAILED로 둔다.
     */
    public static ErrorCode toErrorCode(EcrException e) {
        if (e == null) return ErrorCode.DOWNLOAD_ECR_API_FAILED;

        int sc = e.statusCode();
        String awsCode = awsErrorCode(e);

        // 1) 인증/권한
        if (sc == 401 || sc == 403) {
            return ErrorCode.DOWNLOAD_UNAUTHORIZED;
        }

        // 2) 쓰로틀링(요청 제한)
        if (sc == 429 || isThrottling(awsCode)) {
            return ErrorCode.DOWNLOAD_ECR_THROTTLED;
        }

        // 3) 나머지는 호출자 컨텍스트에서 세부 분기할 수 있게 기본값 반환
        return ErrorCode.DOWNLOAD_ECR_API_FAILED;
    }

    /**
     * awsErrorDetails.errorCode 추출 (null-safe)
     */
    public static String awsErrorCode(EcrException e) {
        if (e == null) return null;
        return (e.awsErrorDetails() != null) ? StringUtils.trimToNull(e.awsErrorDetails().errorCode()) : null;
    }

    /**
     * Throttling 계열 판단
     */
    public static boolean isThrottling(String awsCode) {
        if (awsCode == null) return false;
        return "ThrottlingException".equals(awsCode) || "TooManyRequestsException".equals(awsCode);
    }
}
