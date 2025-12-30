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

@Slf4j
@Service
@RequiredArgsConstructor
public class EcrRepoScanService {

    /**
     * 태그 선택 규칙에서 사용할 기준 태그명
     * - 최신 이미지 후보에 latest 태그가 있으면 우선 선택
     * - 없으면 첫 번째 태그 선택
     * <p>
     * 주의: 이 값 자체가 "항상 존재하는 태그"를 의미하지는 않음.
     * (빈 레포 or 태그 없는 이미지의 경우 latest를 강제로 쓰면 pull 실패하므로, 이 서비스는 pullable=false로 차단함)
     */
    private static final String TAG_LATEST = "latest";

    /**
     * 페이지네이션/스캔 방어값(서비스 다운 방지 목적)
     * - ECR API는 nextToken 기반 페이지네이션이며,
     * 비정상 상황에서 token 반복/과도한 페이지 수로 무한 루프처럼 보일 수 있음.
     * - "과도한 스캔"을 방지하기 위해 상한을 둠.
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
     * (요구사항 #1) 모든 repo를 가져오고, 각 repo의 최신 tag를 계산하여 반환
     * <p>
     * 입력
     * - region, accountId, accessKeyId, secretAccessKey
     * <p>
     * 출력
     * - repo 목록 + repo별 최신 tag(latestTag) + pullable 여부 + 실패 사유(reason)
     * <p>
     * - "빈 레포(이미지 없음)" 또는 "태그/시간 판단 불가"인 레포를 사전에 pullable=false로 표시하여 docker pull 실패를 방지
     * - 최신 태그 판단은 DescribeImages(TAGGED) 페이지를 끝까지 스캔하되, 전체 리스트를 적재하지 않고 최신 후보 1개만 유지(O(1) 메모리)
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
     * accountId(registryId) 기준으로 모든 Repository를 페이지 끝까지 수집
     * <p>
     * 방어 로직
     * - 반복 상한(MAX_PAGINATION_ITERATIONS)
     * - nextToken 반복 반환 감지 (token이 이전과 동일하면 break)
     * <p>
     * 주의
     * - Repository가 많을 수 있으므로, 이 단계는 "리스트 전체 적재"가 발생함.
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
     * "실제 pull 가능한 최신 태그" 계산
     * <p>
     * - 이 서비스는 '최신 후보가 없다/불확실'하면 절대 pull을 시도하지 않도록 pullable=false로 반환
     * 이유: 임의로 latest를 보정해서 pull을 시도하면, 빈 레포에서 "not found" 오류 발생
     * <p>
     * 상태별 처리
     * - NO_TAGGED_IMAGES(=이미지 없음): latestTag=null, pullable=false, reason=NO_IMAGES_IN_REPOSITORY
     * - TAGGED_BUT_NO_PUSHED_AT: latestTag=null, pullable=false (최신 판단 불가)
     * - TIMED_OUT_OR_LIMITED: latestTag=null, pullable=false (제한 초과, pull 금지)
     * - OK:
     * - 최신 pushed 이미지의 tags에서 latest 우선, 없으면 첫 번째
     * - tags가 비정상이면 pull 가능한 tag를 특정할 수 없으므로 pullable=false 처리
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
     * TAGGED 이미지를 스캔하여 "최신 pushed 이미지" 후보 1개만 유지 (메모리 O(1))
     * <p>
     * 이유
     * - ECR의 describeImages는 페이지 단위로 결과를 반환하며,
     * 레포가 매우 큰 경우 전체 적재(List 누적)는 메모리/성능/서비스 안정성 부담 증가
     * - 그래서 "최신 후보 1개"만 유지하면서 페이지를 끝까지 읽도록 설계
     * <p>
     * 방어 정책
     * - REPO_SCAN_BUDGET 초과 시 중단
     * - MAX_PAGINATION_ITERATIONS / MAX_PAGES_PER_REPO 상한
     * - nextToken 반복 감지
     * <p>
     * 결과 상태 구분
     * - NO_TAGGED_IMAGES: TAGGED 결과 자체가 한 번도 안 내려옴(=이미지 없음)
     * - TAGGED_BUT_NO_PUSHED_AT: TAGGED는 있었으나 pushedAt을 끝까지 못 얻음(전부 null 등)
     * - OK: pushedAt 최대값 후보를 얻음
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
     * pull에 사용할 태그 선택 규칙
     * - latest 태그가 있으면 latest 우선
     * - 없으면 첫 번째 태그
     * <p>
     * 주의
     * - tags가 비어있으면 null 반환
     * - 상위 로직에서 null/blank면 pullable=false 처리(=pull 시도 금지)
     */
    private String pickTagForRepoAddr(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        if (tags.contains(TAG_LATEST)) return TAG_LATEST;
        return tags.get(0);
    }

    /**
     * nextToken 정규화
     * - null/blank는 null로 통일
     * - token 비교 정확도를 위해 trim 처리
     */
    private String normalizeToken(String token) {
        return StringUtils.trimToNull(token);
    }

    /**
     * TAGGED 이미지 스캔 결과 상태
     */
    private enum ScanStatus {
        OK,
        NO_TAGGED_IMAGES,
        TAGGED_BUT_NO_PUSHED_AT,
        TIMED_OUT_OR_LIMITED
    }

    /**
     * scanLatestTaggedImage() 반환값
     * <p>
     * - status: OK/NO_TAGGED_IMAGES/TAGGED_BUT_NO_PUSHED_AT/TIMED_OUT_OR_LIMITED
     * - bestPushedAt: 최신 pushedAt (없으면 null)
     * - bestTags: 최신 후보의 tags (없으면 empty)
     * <p>
     * 방어
     * - status는 null 불가
     * - bestTags는 불변 리스트로 copy
     */
    private record LatestScanResult(ScanStatus status, Instant bestPushedAt, List<String> bestTags) {
        LatestScanResult {
            Objects.requireNonNull(status, "status");
            bestTags = (bestTags == null) ? List.of() : List.copyOf(bestTags);
        }
    }

    /**
     * Repo별 최신 태그 계산 결과
     * <p>
     * - latestTag: pull에 사용할 태그(없으면 null)
     * - lastPushedAt: 최신 이미지 pushedAt(없으면 null)
     * - pullable: true면 pull 가능 / false면 pull 금지
     * - reason: pullable=false인 사유 코드(클라이언트에서 pull 시도 차단/로그 분석용)
     */
    private record RepoLatestTagResult(
            String latestTag,
            Instant lastPushedAt,
            boolean pullable,
            String reason
    ) {
    }
}
