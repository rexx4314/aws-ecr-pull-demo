package dev.rex.demo.domain.image;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.*;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 이미지 레퍼런스 결정 도메인 컴포넌트
 * <p>
 * 책임:
 * - 요청 파라미터(resolveLatest/tag/digest)를 “하나의 이미지 참조”로 정규화
 * - 최신(tagged) 이미지에서 최신 태그를 스캔하여 선택(resolveLatest)
 * - tag 기반 요청에 대해 digest를 보정(필요 시)
 *
 * <p>
 * 설계/정책:
 * - tag/digest 교차 검증(둘 중 하나만 허용 등)은 상위 DTO/검증 계층에서 보장한다고 가정
 * - resolveLatest=true인 경우:
 * - TAGGED 이미지를 페이지네이션으로 훑어 pushedAt 최신 이미지를 선택
 * - budget(maxPages/maxImages)을 초과하면 “현재까지의 best”를 반환하거나(tag가 없으면 실패)
 * - “latest” 문자열 자체를 강제하지 않음: ECR에 존재하는 TAGGED 이미지 중 최신 pushedAt의 tag를 선택
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageRefResolver {

    /**
     * describeImages 페이지 크기
     * - 값이 크면 API 호출 횟수는 줄지만 응답이 커질 수 있음
     */
    private static final int DESCRIBE_IMAGES_PAGE_SIZE = 100;

    /**
     * resolveLatest 스캔 시, bestTag를 선택할 때 태그를 어떻게 고를지에 대한 정책:
     * - ECR ImageDetail에는 여러 태그가 붙을 수 있음
     * - 현재 정책은 “첫 번째 태그(tags.get(0))”를 선택
     * (필요하면 "latest 우선" 등으로 확장 가능)
     */
    private static final int TAG_PICK_INDEX = 0;

    /**
     * 요청을 해석하여 최종 이미지 참조를 반환
     * <p>
     * 우선순위:
     * 1) resolveLatest=true -> 최신 TAGGED 이미지 스캔 후 tag 선택
     * 2) digest가 있으면 digest 기반 참조
     * 3) tag 기반 참조
     *
     * @param ecr            ECR 클라이언트
     * @param registryId     레지스트리(계정) ID
     * @param repositoryName 리포지토리 이름
     * @param resolveLatest  최신 선택 여부
     * @param tag            요청 tag
     * @param digest         요청 digest
     * @param maxPages       resolveLatest 스캔 최대 페이지 수
     * @param maxImages      resolveLatest 스캔 최대 이미지 수
     * @return ResolvedImageRef 정규화된 참조(tag or digest)
     */
    public ResolvedImageRef resolve(
            EcrClient ecr,
            String registryId,
            String repositoryName,
            boolean resolveLatest,
            String tag,
            String digest,
            int maxPages,
            int maxImages
    ) {
        // 0) 필수 파라미터 최소 검증(상위 검증이 있더라도, 내부 유틸은 안전하게)
        requireNonBlank(registryId, "registryId");
        requireNonBlank(repositoryName, "repositoryName");
        Objects.requireNonNull(ecr, "ecr");

        // 1) resolveLatest=true이면: TAGGED 이미지 중 pushedAt 최신의 태그를 선택
        if (resolveLatest) {
            LatestSelection sel = scanLatestTaggedImage(ecr, registryId, repositoryName, maxPages, maxImages);

            // 최신을 요청했는데 tag를 못 찾으면 repo에 TAGGED 이미지가 없다는 의미일 가능성이 큼
            if (sel.tag() == null) {
                throw new ApiException(
                        ErrorCode.NO_IMAGES_IN_REPOSITORY,
                        "resolveLatest=true 이지만 TAGGED 이미지가 없습니다(repo empty 가능)."
                );
            }

            // tag 기반 참조로 반환(다운로드/manifest fetch에서 tag가 필요)
            return ResolvedImageRef.byTag(sel.tag());
        }

        // 2) 일반 모드: digest 우선, 없으면 tag 사용
        String t = StringUtils.trimToNull(tag);
        String d = StringUtils.trimToNull(digest);

        // 교차 검증(둘 다 없음/둘 다 있음 등)은 DTO(@AssertTrue 등)에서 보장한다고 가정
        if (d != null) return ResolvedImageRef.byDigest(d);

        // tag가 null이면 byTag 내부에서 trimToNull 처리되므로 그대로 전달
        return ResolvedImageRef.byTag(t);
    }

    /**
     * tag 기반 참조인 경우 digest를 조회하여 반환
     * <p>
     * - 이미 digest 기반이면 그대로 반환(추가 조회 없음)
     * - tag 기반이면 describeImages(imageTag=tag)로 digest를 얻어옴
     * - 조회 실패/미존재 시 null 반환(정책: “보정 실패는 치명적 오류로 보지 않음”)
     *
     * @param ecr            ECR 클라이언트
     * @param registryId     레지스트리 ID
     * @param repositoryName 리포지토리 이름
     * @param ref            resolve() 결과 참조
     * @return digest 문자열 또는 null
     */
    public String resolveDigestIfNeeded(EcrClient ecr, String registryId, String repositoryName, ResolvedImageRef ref) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(ecr, "ecr");
        requireNonBlank(registryId, "registryId");
        requireNonBlank(repositoryName, "repositoryName");

        // 1) 이미 digest 기반이면 추가 조회 없이 그대로 반환
        if (ref.isDigest()) return ref.requestedDigest();

        // 2) tag가 없으면 조회 불가 -> null
        String tag = ref.resolvedTag();
        if (tag == null) return null;

        // 3) tag -> digest 조회(실패해도 null)
        return tryResolveDigestByTag(ecr, registryId, repositoryName, tag);
    }

    /**
     * resolveLatest 구현:
     * TAGGED 이미지들을 describeImages로 페이지네이션 스캔하며,
     * pushedAt이 가장 최신인 ImageDetail의 tag를 선택
     *
     * <p>
     * budget:
     * - maxPages: 페이지 수 제한
     * - maxImages: imageDetails 항목 수 제한
     *
     * @return LatestSelection(tag, pushedAt)
     */
    private LatestSelection scanLatestTaggedImage(
            EcrClient ecr,
            String registryId,
            String repositoryName,
            int maxPages,
            int maxImages
    ) {
        // 0) budget 값은 상위에서 보정되었겠지만, 내부에서도 최소 안전장치
        int safeMaxPages = positiveOrDefault(maxPages, 1);
        int safeMaxImages = positiveOrDefault(maxImages, 1);

        // 1) 페이지네이션 토큰
        String nextToken = null;

        // 2) 진행 카운터
        int pages = 0;
        int scanned = 0;

        // 3) best 후보
        Instant bestPushedAt = null;
        String bestTag = null;

        do {
            // A) maxPages 제한 체크(무한 스캔 방지)
            pages++;
            if (pages > safeMaxPages) {
                log.warn("resolveLatest: reached maxPages. repo={}, maxPages={}", repositoryName, safeMaxPages);
                break;
            }

            // B) TAGGED 이미지 목록 조회
            // - filter(tagStatus=TAGGED)로 태그 없는 이미지 제외
            DescribeImagesResponse resp = ecr.describeImages(
                    DescribeImagesRequest.builder()
                            .registryId(registryId)
                            .repositoryName(repositoryName)
                            .filter(DescribeImagesFilter.builder().tagStatus(TagStatus.TAGGED).build())
                            .nextToken(nextToken)
                            .maxResults(DESCRIBE_IMAGES_PAGE_SIZE)
                            .build()
            );

            // C) 응답을 순회하며 최신 pushedAt을 찾음
            List<ImageDetail> details = resp.imageDetails();
            if (details != null && !details.isEmpty()) {
                for (ImageDetail d : details) {
                    scanned++;

                    // C-1) maxImages 제한 체크(너무 큰 repo에서 과도 스캔 방지)
                    if (scanned > safeMaxImages) {
                        log.warn("resolveLatest: reached maxImages. repo={}, maxImages={}", repositoryName, safeMaxImages);
                        return new LatestSelection(bestTag, bestPushedAt);
                    }

                    // C-2) pushedAt/태그가 있어야 “repo:tag”를 만들 수 있으므로 없으면 skip
                    Instant pushedAt = d.imagePushedAt();
                    List<String> tags = d.imageTags();
                    if (pushedAt == null || tags == null || tags.isEmpty()) continue;

                    // C-3) 최신 pushedAt이면 best 갱신
                    if (bestPushedAt == null || pushedAt.isAfter(bestPushedAt)) {
                        bestPushedAt = pushedAt;

                        // 태그 선택 정책(현재: 첫 번째 태그)
                        bestTag = tags.get(TAG_PICK_INDEX);
                    }
                }
            }

            // D) 다음 페이지로 이동(공백/빈 값은 null 처리)
            nextToken = normalizeToken(resp.nextToken());

        } while (nextToken != null);

        // E) 스캔 종료: bestTag가 null이면 TAGGED 이미지가 없었거나 pushedAt/tags가 없었던 경우
        return new LatestSelection(bestTag, bestPushedAt);
    }

    /**
     * tag -> digest 조회
     * <p>
     * - describeImages(imageIds=imageTag)로 단일 태그를 조회
     * - 실패 시 null 반환(상위 정책: digest 보정은 “best-effort”)
     */
    private String tryResolveDigestByTag(EcrClient ecr, String registryId, String repositoryName, String tag) {
        try {
            // 1) 특정 tag에 매칭되는 이미지 상세 조회
            DescribeImagesResponse resp = ecr.describeImages(
                    DescribeImagesRequest.builder()
                            .registryId(registryId)
                            .repositoryName(repositoryName)
                            .imageIds(ImageIdentifier.builder().imageTag(tag).build())
                            .build()
            );

            // 2) 결과가 없으면 null
            if (resp.imageDetails() == null || resp.imageDetails().isEmpty()) return null;

            // 3) 첫 번째 결과의 digest 반환
            // (동일 tag가 여러 digest를 가질 수 없다는 전제: ECR의 tag는 특정 이미지에 매핑)
            return resp.imageDetails().get(0).imageDigest();

        } catch (EcrException ex) {
            // ECR API 오류(권한/스로틀/네트워크 등) -> 로그만 남기고 null
            // - 여기서 예외로 터뜨리면 다운로드 자체가 불필요하게 실패할 수 있어 정책상 best-effort로 둠
            log.warn("resolve digest by tag failed (ECR). repo={}, tag={}, status={}, awsCode={}, err={}",
                    repositoryName,
                    tag,
                    ex.statusCode(),
                    ex.awsErrorDetails() == null ? null : ex.awsErrorDetails().errorCode(),
                    ex.getMessage());
            return null;

        } catch (Exception ex) {
            // 그 외 예외도 동일하게 best-effort 처리
            log.warn("resolve digest by tag failed. repo={}, tag={}, err={}", repositoryName, tag, ex.getMessage());
            return null;
        }
    }

    /**
     * nextToken 정규화:
     * - 공백/빈 문자열을 null로 처리하여 루프 조건을 단순화
     */
    private String normalizeToken(String token) {
        return StringUtils.trimToNull(token);
    }

    /**
     * 공통 유효성 체크: blank이면 INVALID_REQUEST로 처리하는 대신
     * - 이 도메인 컴포넌트는 상위 계층에서 이미 검증된 값을 받는 것이 일반적이므로
     * - 여기서는 IllegalArgumentException 대신 NPE/IAE로 빠르게 실패시키되,
     * 메시지는 디버깅 가능한 형태로 유지
     */
    private void requireNonBlank(String v, String name) {
        if (StringUtils.isBlank(v)) {
            throw new IllegalArgumentException(name + " is required.");
        }
    }

    /**
     * 양수 보정: v가 0 이하이면 기본값 반환
     */
    private int positiveOrDefault(int v, int def) {
        return (v <= 0) ? def : v;
    }

    /**
     * resolveLatest 스캔 결과(최신으로 선택된 tag와 pushedAt)
     */
    private record LatestSelection(String tag, Instant pushedAt) {
    }

    /**
     * 이미지 레퍼런스(해석 결과)
     * <p>
     * - isDigest=true  -> requestedDigest 사용
     * - isDigest=false -> resolvedTag 사용
     */
    public record ResolvedImageRef(boolean isDigest, String resolvedTag, String requestedDigest) {

        /**
         * tag 기반 참조 생성
         * - tag는 trimToNull 처리하여 공백 입력을 제거
         */
        public static ResolvedImageRef byTag(String tag) {
            return new ResolvedImageRef(false, StringUtils.trimToNull(tag), null);
        }

        /**
         * digest 기반 참조 생성
         * - digest는 trimToNull 처리
         */
        public static ResolvedImageRef byDigest(String digest) {
            return new ResolvedImageRef(true, null, StringUtils.trimToNull(digest));
        }
    }
}
