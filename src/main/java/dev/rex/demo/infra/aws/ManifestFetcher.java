package dev.rex.demo.infra.aws;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.domain.image.ImageRefResolver;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ECR Manifest 조회 컴포넌트
 * <p>
 * 책임:
 * - ECR BatchGetImage 호출로 manifest(JSON) 조회
 * - 결과 검증(이미지/manifest 존재 여부)
 * - AWS SDK 예외(EcrException)를 서비스 정책(ErrorCode)으로 매핑하여 ApiException으로 래핑
 *
 * <p>
 * 설계 포인트(팀 공유용):
 * - fetchManifestJson()는 “흐름(오케스트레이션)”만 남기고, 세부는 private 메서드로 분리
 * - ref(tag/digest) 분기를 ImageIdentifier 생성으로 캡슐화
 * - 예외 분류 로직은 한 곳(classifyEcrException)에서만 수행(중복 제거)
 * - “없음/비어있음” 검증은 guard clause로 빠르게 실패
 */
@Component
public class ManifestFetcher {

    /**
     * BatchGetImage를 호출하여 manifest JSON을 가져옴
     * <p>
     * 처리 흐름:
     * 1) 입력 검증(필수값 null/blank 방어)
     * 2) ref(tag/digest)에 따라 ImageIdentifier 구성
     * 3) BatchGetImage 호출
     * 4) 응답 검증: images[0].imageManifest 존재 확인
     * 5) manifest JSON 반환
     *
     * <p>
     * 실패 정책:
     * - manifest가 없으면: DOWNLOAD_ECR_BLOB_NOT_FOUND (tag/digest 유효성 문제로 취급)
     * - EcrException 발생 시: statusCode/awsErrorCode 기반으로 ErrorCode 분류 후 ApiException 래핑
     */
    public String fetchManifestJson(
            EcrClient ecr,
            String registryId,
            String repositoryName,
            ImageRefResolver.ResolvedImageRef ref
    ) {
        // 0) 필수 입력 방어: NPE/의미없는 API 호출 방지
        Objects.requireNonNull(ecr, "ecr");
        String reg = requireText(registryId, "registryId");
        String repo = requireText(repositoryName, "repositoryName");
        Objects.requireNonNull(ref, "ref");

        try {
            // 1) 요청 식별자 구성: tag vs digest
            ImageIdentifier id = toImageIdentifier(ref);

            // 2) ECR 호출: manifest는 BatchGetImage에서 imageManifest로 내려옴
            BatchGetImageResponse resp = ecr.batchGetImage(
                    BatchGetImageRequest.builder()
                            .registryId(reg)
                            .repositoryName(repo)
                            .imageIds(id)
                            .build()
            );

            // 3) 응답 검증: manifest가 없으면 "요청한 이미지가 없다/조회 불가"로 처리
            //    - AWS가 images 대신 failures 리스트로 내려주는 케이스도 있어 방어적으로 검사
            String manifest = extractManifestOrNull(resp);
            if (manifest == null) {
                throw new ApiException(
                        ErrorCode.DOWNLOAD_ECR_BLOB_NOT_FOUND,
                        "BatchGetImage 결과에 imageManifest가 없습니다. tag/digest가 유효한지 확인하세요.",
                        Map.of(
                                "repositoryName", repo,
                                "refType", ref.isDigest() ? "digest" : "tag",
                                "ref", safe(ref.isDigest() ? ref.requestedDigest() : ref.resolvedTag())
                        )
                );
            }

            // 4) 정상 반환: manifest JSON 문자열(원문)
            return manifest;

        } catch (ApiException ae) {
            // 5) 이미 정책에 맞게 구성된 예외는 그대로 전파
            throw ae;

        } catch (EcrException ee) {
            // 6) AWS SDK 예외를 서비스 정책(ErrorCode)으로 변환해 래핑
            ErrorCode code = classifyEcrException(ee);

            throw new ApiException(
                    code,
                    "ECR BatchGetImage 실패: " + safeAwsMsg(ee),
                    Map.of(
                            "status", ee.statusCode(),
                            "repositoryName", repo
                    ),
                    ee
            );
        }
    }

    /**
     * ref(tag/digest)에 따라 ImageIdentifier 생성
     * <p>
     * - digest 기반이면 imageDigest 사용
     * - tag 기반이면 imageTag 사용
     *
     * <p>
     * 주의:
     * - ResolvedImageRef는 호출 측에서 유효성을 보장한다고 가정하지만,
     * tag/digest가 null일 수 있는 경계 케이스도 대비해 빠르게 실패시킴
     */
    private ImageIdentifier toImageIdentifier(ImageRefResolver.ResolvedImageRef ref) {
        if (ref.isDigest()) {
            String digest = StringUtils.trimToNull(ref.requestedDigest());
            if (digest == null) throw new IllegalArgumentException("requestedDigest is blank");
            return ImageIdentifier.builder().imageDigest(digest).build();
        }

        String tag = StringUtils.trimToNull(ref.resolvedTag());
        if (tag == null) throw new IllegalArgumentException("resolvedTag is blank");
        return ImageIdentifier.builder().imageTag(tag).build();
    }

    /**
     * BatchGetImageResponse에서 manifest를 추출
     * <p>
     * AWS 응답 형태:
     * - resp.images(): 성공한 항목 리스트
     * - resp.failures(): 실패 항목 리스트 (이미지 미존재/권한/기타)
     * <p>
     * 여기서는 "images[0].imageManifest"를 우선으로 사용하며,
     * 없으면 null을 반환하여 호출자가 정책적으로 처리하게 함
     */
    private String extractManifestOrNull(BatchGetImageResponse resp) {
        if (resp == null) return null;

        List<Image> images = resp.images();
        if (images == null || images.isEmpty()) {
            // failures가 존재할 수 있으나, 메시지/코드 매핑은 상위에서 처리(일관성)
            return null;
        }

        Image first = images.get(0);
        if (first == null) return null;

        // manifest JSON은 imageManifest 필드에 들어있음
        return first.imageManifest();
    }

    /**
     * EcrException을 서비스 ErrorCode로 분류
     * <p>
     * 분류 기준:
     * - 401/403: DOWNLOAD_UNAUTHORIZED
     * - 429/Throttling: DOWNLOAD_ECR_THROTTLED
     * - RepositoryNotFoundException: DOWNLOAD_ECR_REPOSITORY_NOT_FOUND
     * - ImageNotFoundException 또는 404: DOWNLOAD_ECR_BLOB_NOT_FOUND
     * - 그 외: DOWNLOAD_ECR_API_FAILED
     *
     * <p>
     * 주의:
     * - ECR은 statusCode와 awsErrorDetails.errorCode가 함께/다르게 올 수 있으므로
     * 둘을 동시에 참고하는 방식으로 구현
     */
    private ErrorCode classifyEcrException(EcrException e) {
        // 1) 공통 분류는 공용 매퍼에 위임
        ErrorCode base = EcrErrorMapper.toErrorCode(e);
        if (base != ErrorCode.DOWNLOAD_ECR_API_FAILED) return base;

        // 2) ManifestFetcher 컨텍스트에서만 필요한 세부 분류
        String awsCode = EcrErrorMapper.awsErrorCode(e);
        int sc = (e == null) ? 0 : e.statusCode();

        // 3) 리포지토리/이미지 미존재
        if ("RepositoryNotFoundException".equals(awsCode)) return ErrorCode.DOWNLOAD_ECR_REPOSITORY_NOT_FOUND;
        if ("ImageNotFoundException".equals(awsCode)) return ErrorCode.DOWNLOAD_ECR_BLOB_NOT_FOUND;
        if (sc == 404) return ErrorCode.DOWNLOAD_ECR_BLOB_NOT_FOUND;

        // 4) 그 외
        return ErrorCode.DOWNLOAD_ECR_API_FAILED;
    }

    /**
     * AWS 에러 메시지 추출(사용자 로그/응답 메시지용)
     * <p>
     * - awsErrorDetails.errorMessage 우선
     * - 없으면 exception message 사용
     */
    private String safeAwsMsg(EcrException e) {
        String m = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorMessage() : null;
        if (StringUtils.isBlank(m)) m = e.getMessage();
        return StringUtils.defaultString(m);
    }

    /**
     * 필수 문자열 입력 검증(공백/빈 문자열 불허)
     */
    private String requireText(String v, String name) {
        Objects.requireNonNull(name, "name");
        String t = StringUtils.trimToNull(v);
        if (t == null) throw new IllegalArgumentException(name + " is blank");
        return t;
    }

    /**
     * 로깅/메타데이터 맵에 넣기 위해 null-safe 문자열로 변환
     * - null/blank는 빈 문자열로 통일하여 Map.of 제약(null 불가)을 회피
     */
    private String safe(String s) {
        String t = StringUtils.trimToNull(s);
        return (t == null) ? "" : t;
    }
}
