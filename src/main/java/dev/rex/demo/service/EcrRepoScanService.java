package dev.rex.demo.service;

import dev.rex.demo.api.dto.EcrRepoItem;
import dev.rex.demo.api.dto.EcrRepoScanRequest;
import dev.rex.demo.api.dto.EcrRepoScanResponse;
import dev.rex.demo.ecr.AwsEcrClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ECR Repository 스캔 서비스
 * <p>
 * 주요 기능
 * - AWS ECR 전체 Repository 목록 조회
 * - 각 Repository의 최신 태그(latest tag) 자동 계산
 * - Pull 가능 여부 사전 판단 (빈 레포/불확실한 레포는 pullable=false 처리)
 * <p>
 * 설계 특징
 * - 메모리 효율: 최신 이미지 후보 1개만 유지 (O(1) 메모리)
 * - 서비스 보호: 타임아웃/페이지 상한/반복 상한으로 과도한 스캔 방지
 * - 안정성: 빈 레포/태그 불확실 시 pullable=false로 사전 차단하여 pull 실패 방지
 * <p>
 * 태그 선택 규칙
 * - latest 태그 우선
 * - 없으면 첫 번째 태그
 * - 태그가 없으면 pullable=false
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcrRepoScanService {

    /**
     * 태그 선택 규칙에서 사용할 기준 태그명
     * - 최신 이미지 후보에 latest 태그가 있으면 우선 선택
     * - 없으면 첫 번째 태그 선택
     * <p>
     * 주의: 이 값 자체가 "항상 존재하는 태그"를 의미하지는 않음
     * (빈 레포 or 태그 없는 이미지의 경우 latest를 강제로 쓰면 pull 실패하므로, 이 서비스는 pullable=false로 차단함)
     */
    private static final String TAG_LATEST = "latest";

    /**
     * 페이지네이션/스캔 방어값(서비스 다운 방지)
     * - ECR API는 nextToken 기반 페이지네이션이며,
     * 비정상 상황에서 token 반복/과도한 페이지 수로 무한 루프처럼 보일 수 있음
     * - "과도한 스캔"을 방지하기 위해 상한을 둠
     */
    private static final int MAX_PAGINATION_ITERATIONS = 10_000;
    private static final int MAX_PAGES_PER_REPO = 2_000;
    private static final Duration REPO_SCAN_BUDGET = Duration.ofSeconds(15);

    /**
     * AWS 호출 횟수 감소 목적의 페이지 크기
     * - describeRepositories / describeImages 호출을 최대한 적게 하도록 maxResults를 크게 잡음
     * - 단, 너무 크게 잡으면 응답 payload가 커져 네트워크/메모리 부담이 증가할 수 있음
     */
    private static final int DESCRIBE_IMAGES_MAX_RESULTS = 500;
    private static final int DESCRIBE_REPOSITORIES_MAX_RESULTS = 1000;

    /**
     * ECR Client 생성 책임 분리
     * - region/credential 설정 + timeout/retry 전략은 Factory에서 통일 관리
     * - Service는 "스캔/판단"에만 집중
     */
    private final AwsEcrClientFactory ecrClientFactory;

    /**
     * 모든 ECR Repository 스캔 및 최신 태그 계산
     * <p>
     * 입력
     * - region, accountId, accessKeyId, secretAccessKey
     * <p>
     * 출력
     * - Repository 목록 + 각 Repository별 최신 태그 + Pull 가능 여부
     * <p>
     * 특징
     * - 빈 레포 또는 태그 판단 불가 시 pullable=false 처리
     * - 최신 태그 판단 시 전체 리스트 적재하지 않고 최신 후보 1개만 유지
     *
     * @param req AWS 자격증명 및 리전 정보
     * @return Repository 스캔 결과 (개수 + 상세 목록)
     */
    public EcrRepoScanResponse scanAllReposWithLatestTag(EcrRepoScanRequest req) {
        // try-with-resources: EcrClient는 close 필요
        try (EcrClient ecr = ecrClientFactory.create(req.region(), req.accessKeyId(), req.secretAccessKey())) {

            // 1) 모든 Repository 조회 (페이지네이션 끝까지)
            List<Repository> repos = listAllRepositories(ecr, req.accountId());

            // 2) 각 Repository별 최신 태그 계산
            List<EcrRepoItem> items = new ArrayList<>(repos.size());
            for (Repository r : repos) {

                // Repo별 최신 태그 계산 결과(빈 레포면 pullable=false)
                RepoLatestTagResult t = computeLatestTagForRepo(ecr, req.accountId(), r.repositoryName());

                // 응답 DTO로 변환
                items.add(new EcrRepoItem(
                        r.repositoryName(),
                        r.repositoryUri(),
                        t.latestTag(),       // 빈 레포/판단불가면 null
                        t.lastPushedAt(),    // 없으면 null
                        t.pullable(),        // pull 가능한 repo인지(사전 차단)
                        t.reason()           // pull 불가 사유
                ));
            }

            return new EcrRepoScanResponse(items.size(), items);
        }
    }

    /**
     * 모든 Repository 목록 조회 (페이지네이션 끝까지)
     * <p>
     * 방어 로직
     * - 반복 상한 (MAX_PAGINATION_ITERATIONS)
     * - nextToken 반복 감지
     *
     * @param ecr       ECR 클라이언트
     * @param accountId AWS 계정 ID
     * @return Repository 목록
     */
    private List<Repository> listAllRepositories(EcrClient ecr, String accountId) {
        List<Repository> repos = new ArrayList<>();
        String nextToken = null;
        int iterations = 0;

        do {
            iterations++;
            if (iterations > MAX_PAGINATION_ITERATIONS) {
                // 서비스 보호: 반복 상한 초과 시 중단
                log.warn("describeRepositories iterations limit exceeded. accountId={}, iterations={}", accountId, iterations);
                break;
            }

            DescribeRepositoriesResponse resp = ecr.describeRepositories(
                    DescribeRepositoriesRequest.builder()
                            .registryId(accountId)
                            .maxResults(DESCRIBE_REPOSITORIES_MAX_RESULTS)
                            .nextToken(nextToken)
                            .build()
            );

            if (resp.repositories() != null) repos.addAll(resp.repositories());

            String prev = normalizeToken(nextToken);
            nextToken = normalizeToken(resp.nextToken());

            // 무한 루프 방지: nextToken이 반복되면 비정상으로 보고 중단
            if (nextToken != null && nextToken.equals(prev)) {
                log.warn("describeRepositories nextToken repeated. accountId={}, token={}", accountId, nextToken);
                break;
            }
        } while (nextToken != null);

        return repos;
    }

    /**
     * Repository의 Pull 가능한 최신 태그 계산
     * <p>
     * 상태별 처리
     * - NO_TAGGED_IMAGES: pullable=false (이미지 없음)
     * - TAGGED_BUT_NO_PUSHED_AT: pullable=false (최신 판단 불가)
     * - TIMED_OUT_OR_LIMITED: pullable=false (제한 초과)
     * - OK: 최신 이미지의 태그에서 latest 우선 선택
     *
     * @param ecr            ECR 클라이언트
     * @param accountId      AWS 계정 ID
     * @param repositoryName Repository 이름
     * @return 최신 태그 계산 결과 (태그, 시간, Pull 가능 여부)
     */
    private RepoLatestTagResult computeLatestTagForRepo(EcrClient ecr, String accountId, String repositoryName) {
        // Repo 내 TAGGED 이미지들 중 최신 후보 1개(bestPushedAt/bestTags)만 뽑아옴
        LatestScanResult scan = scanLatestTaggedImage(ecr, accountId, repositoryName);

        return switch (scan.status()) {
            case NO_TAGGED_IMAGES -> new RepoLatestTagResult(
                    null,
                    null,
                    false,
                    "NO_IMAGES_IN_REPOSITORY"
            );

            case TAGGED_BUT_NO_PUSHED_AT -> {
                // TAGGED는 있는데 pushedAt이 모두 null 등으로 최신 판단 불가
                log.warn("TAGGED exists but pushedAt not available. repo={}", repositoryName);
                yield new RepoLatestTagResult(
                        null,
                        null,
                        false,
                        "TAGGED_EXISTS_BUT_PUSHED_AT_UNKNOWN"
                );
            }

            case TIMED_OUT_OR_LIMITED -> {
                // time/page/iteration 제한으로 인해 스캔 중단 -> 결과 신뢰 불가, pull 금지
                log.warn("Repo scan timed out/limited. repo={}", repositoryName);
                yield new RepoLatestTagResult(
                        null,
                        null,
                        false,
                        "SCAN_TIMED_OUT_OR_LIMITED"
                );
            }

            case OK -> {
                // 최신 후보 이미지의 tags에서 pull에 사용할 tag를 결정
                String tag = pickTagForRepoAddr(scan.bestTags());

                if (StringUtils.isBlank(tag)) {
                    // tags 자체가 없거나 비정상인 경우: pull 가능한 태그를 특정할 수 없음
                    yield new RepoLatestTagResult(
                            null,
                            scan.bestPushedAt(),
                            false,
                            "LATEST_IMAGE_HAS_NO_TAGS"
                    );
                }
                yield new RepoLatestTagResult(
                        tag,
                        scan.bestPushedAt(),
                        true,
                        null
                );
            }
        };
    }

    /**
     * TAGGED 이미지 스캔 (메모리 O(1))
     * <p>
     * 특징
     * - 최신 후보 1개만 유지하며 페이지를 끝까지 읽음
     * - 전체 리스트를 적재하지 않아 메모리 효율적
     * <p>
     * 방어 정책
     * - REPO_SCAN_BUDGET 초과 시 중단
     * - MAX_PAGINATION_ITERATIONS / MAX_PAGES_PER_REPO 상한
     * - nextToken 반복 감지
     *
     * @param ecr            ECR 클라이언트
     * @param accountId      AWS 계정 ID
     * @param repositoryName Repository 이름
     * @return 스캔 결과 (상태, 최신 시간, 태그 목록)
     */
    private LatestScanResult scanLatestTaggedImage(EcrClient ecr, String accountId, String repositoryName) {
        Instant startedAt = Instant.now();
        String nextToken = null;
        int iterations = 0;
        int pages = 0;

        // TAGGED 결과가 "한 번이라도" 있었는지 구분하기 위한 플래그
        boolean sawTagged = false;

        // 최신 후보 1개만 유지
        Instant bestPushedAt = null;
        List<String> bestTags = List.of();

        do {
            // (1) 시간 budget 방어
            if (Duration.between(startedAt, Instant.now()).compareTo(REPO_SCAN_BUDGET) > 0) {
                return new LatestScanResult(ScanStatus.TIMED_OUT_OR_LIMITED, bestPushedAt, bestTags);
            }
            // (2) 반복 상한 방어
            if ((iterations + 1) > MAX_PAGINATION_ITERATIONS) {
                return new LatestScanResult(ScanStatus.TIMED_OUT_OR_LIMITED, bestPushedAt, bestTags);
            }
            // (3) repo별 페이지 상한 방어
            if ((pages + 1) > MAX_PAGES_PER_REPO) {
                return new LatestScanResult(ScanStatus.TIMED_OUT_OR_LIMITED, bestPushedAt, bestTags);
            }

            iterations++;
            pages++;

            // TAGGED만 조회(=태그가 있는 이미지 대상으로 최신을 판단)
            DescribeImagesResponse resp = ecr.describeImages(
                    DescribeImagesRequest.builder()
                            .registryId(accountId)
                            .repositoryName(repositoryName)
                            .filter(f -> f.tagStatus(TagStatus.TAGGED))
                            .maxResults(DESCRIBE_IMAGES_MAX_RESULTS)
                            .nextToken(nextToken)
                            .build()
            );

            List<ImageDetail> details = resp.imageDetails();

            if (details != null && !details.isEmpty()) {
                // TAGGED 결과가 내려왔으므로 "TAGGED 존재"로 기록
                sawTagged = true;

                // 최신 후보(bestPushedAt) 갱신
                for (ImageDetail d : details) {
                    Instant pushedAt = d.imagePushedAt();
                    if (pushedAt == null) continue;

                    if (bestPushedAt == null || pushedAt.isAfter(bestPushedAt)) {
                        bestPushedAt = pushedAt;
                        bestTags = (d.imageTags() == null) ? List.of() : d.imageTags();
                    }
                }
            }

            String prev = normalizeToken(nextToken);
            nextToken = normalizeToken(resp.nextToken());

            // nextToken 반복 감지: 비정상 상황 -> 중단 처리
            if (nextToken != null && nextToken.equals(prev)) {
                return new LatestScanResult(ScanStatus.TIMED_OUT_OR_LIMITED, bestPushedAt, bestTags);
            }

        } while (nextToken != null);

        // pushedAt을 하나도 못 얻은 경우: "정말 이미지가 없음" vs "있는데 pushedAt이 없음"을 분리
        if (bestPushedAt == null) {
            if (sawTagged) return new LatestScanResult(ScanStatus.TAGGED_BUT_NO_PUSHED_AT, null, List.of());
            return new LatestScanResult(ScanStatus.NO_TAGGED_IMAGES, null, List.of());
        }

        return new LatestScanResult(ScanStatus.OK, bestPushedAt, bestTags);
    }

    /**
     * Pull에 사용할 태그 선택
     * <p>
     * 규칙
     * - latest 태그 우선
     * - 없으면 첫 번째 태그
     * - tags가 비어있으면 null 반환 (상위에서 pullable=false 처리)
     *
     * @param tags 태그 목록
     * @return 선택된 태그 (없으면 null)
     */
    private String pickTagForRepoAddr(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        if (tags.contains(TAG_LATEST)) return TAG_LATEST;
        return tags.get(0);
    }

    /**
     * nextToken 정규화
     * <p>
     * - null/blank는 null로 통일
     * - token 비교 정확도를 위해 trim 처리
     *
     * @param token 토큰 문자열
     * @return 정규화된 토큰 (null 또는 trimmed)
     */
    private String normalizeToken(String token) {
        return StringUtils.trimToNull(token);
    }

    /**
     * TAGGED 이미지 스캔 결과 상태
     * <p>
     * - OK: 정상적으로 최신 이미지 판단 완료
     * - NO_TAGGED_IMAGES: TAGGED 이미지가 없음 (빈 레포)
     * - TAGGED_BUT_NO_PUSHED_AT: TAGGED는 있으나 pushedAt 판단 불가
     * - TIMED_OUT_OR_LIMITED: 시간/페이지/반복 제한 초과
     */
    private enum ScanStatus {
        OK,
        NO_TAGGED_IMAGES,
        TAGGED_BUT_NO_PUSHED_AT,
        TIMED_OUT_OR_LIMITED
    }

    /**
     * 최신 이미지 스캔 결과 DTO
     * <p>
     * - status: 스캔 상태
     * - bestPushedAt: 최신 이미지 Push 시간 (없으면 null)
     * - bestTags: 최신 이미지의 태그 목록 (없으면 empty)
     *
     * @param status       스캔 상태 (null 불가)
     * @param bestPushedAt 최신 Push 시간
     * @param bestTags     최신 이미지 태그 목록 (불변)
     */
    private record LatestScanResult(ScanStatus status, Instant bestPushedAt, List<String> bestTags) {
        LatestScanResult {
            Objects.requireNonNull(status, "status");
            bestTags = (bestTags == null) ? List.of() : List.copyOf(bestTags);
        }
    }

    /**
     * Repository별 최신 태그 계산 결과 DTO
     * <p>
     * - latestTag: Pull에 사용할 태그 (없으면 null)
     * - lastPushedAt: 최신 이미지 Push 시간 (없으면 null)
     * - pullable: Pull 가능 여부 (true=가능, false=금지)
     * - reason: pullable=false인 경우 사유 코드
     *
     * @param latestTag    최신 태그
     * @param lastPushedAt 최신 Push 시간
     * @param pullable     Pull 가능 여부
     * @param reason       Pull 불가 사유
     */
    private record RepoLatestTagResult(
            String latestTag,
            Instant lastPushedAt,
            boolean pullable,
            String reason
    ) {
    }
}
