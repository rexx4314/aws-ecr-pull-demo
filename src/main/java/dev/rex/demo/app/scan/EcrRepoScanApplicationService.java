package dev.rex.demo.app.scan;

import dev.rex.demo.ecr.api.dto.EcrRepoItem;
import dev.rex.demo.ecr.api.dto.EcrRepoScanRequest;
import dev.rex.demo.ecr.api.dto.EcrRepoScanResponse;
import dev.rex.demo.infra.aws.EcrClientFactory;
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
 * Repo 스캔 서비스
 * - Repository 목록 조회
 * - Repo별 latest tag(=TAGGED 중 pushedAt 최신) 계산
 * - pullable/reason 산출
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcrRepoScanApplicationService {

    private static final String TAG_LATEST = "latest";

    // 무한 루프 방어
    private static final int MAX_PAGINATION_ITERATIONS = 10_000;
    private static final int MAX_PAGES_PER_REPO = 2_000;

    // Repo 스캔 budget
    private static final Duration REPO_SCAN_BUDGET = Duration.ofSeconds(15);

    // 페이지 크기
    private static final int DESCRIBE_IMAGES_MAX_RESULTS = 500;
    private static final int DESCRIBE_REPOSITORIES_MAX_RESULTS = 1000;

    private final EcrClientFactory ecrClientFactory;

    public EcrRepoScanResponse scanAllReposWithLatestTag(EcrRepoScanRequest req) {
        req.validate(); // 수동 검증

        try (EcrClient ecr = ecrClientFactory.create(req.region(), req.accessKeyId(), req.secretAccessKey(), null)) {

            // repo 목록
            List<Repository> repos = listAllRepositories(ecr, req.accountId());

            // repo별 계산
            List<EcrRepoItem> items = new ArrayList<>(repos.size());
            for (Repository r : repos) {
                RepoLatestTagResult t = computeLatestTagForRepo(ecr, req.accountId(), r.repositoryName());

                items.add(new EcrRepoItem(
                        r.repositoryName(),
                        r.repositoryUri(),
                        t.latestTag(),
                        t.lastPushedAt(),
                        t.pullable(),
                        t.reason()
                ));
            }

            return new EcrRepoScanResponse(items.size(), items);
        }
    }

    /**
     * 단일 repo 스캔
     * - latestTag/pullable/reason 계산
     */
    public EcrRepoItem scanOneRepoWithLatestTag(EcrRepoScanRequest req, String repositoryName) {
        req.validate(); // 수동 검증

        String repo = StringUtils.trimToNull(repositoryName);
        if (repo == null) throw new IllegalArgumentException("repositoryName은 필수입니다.");

        try (EcrClient ecr = ecrClientFactory.create(req.region(), req.accessKeyId(), req.secretAccessKey(), null)) {

            DescribeRepositoriesResponse r = ecr.describeRepositories(DescribeRepositoriesRequest.builder()
                    .registryId(req.accountId())
                    .repositoryNames(repo)
                    .build());

            if (r.repositories() == null || r.repositories().isEmpty()) {
                return new EcrRepoItem(repo, null, null, null, false, "REPOSITORY_NOT_FOUND");
            }

            Repository rr = r.repositories().get(0);
            RepoLatestTagResult t = computeLatestTagForRepo(ecr, req.accountId(), rr.repositoryName());

            return new EcrRepoItem(
                    rr.repositoryName(),
                    rr.repositoryUri(),
                    t.latestTag(),
                    t.lastPushedAt(),
                    t.pullable(),
                    t.reason()
            );
        }
    }

    // repo 목록 전체 조회
    private List<Repository> listAllRepositories(EcrClient ecr, String accountId) {
        List<Repository> repos = new ArrayList<>();
        String nextToken = null;
        int iterations = 0;

        do {
            iterations++;
            if (iterations > MAX_PAGINATION_ITERATIONS) {
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

            // nextToken 반복 방지
            if (nextToken != null && nextToken.equals(prev)) {
                log.warn("describeRepositories nextToken repeated. accountId={}, token={}", accountId, nextToken);
                break;
            }
        } while (nextToken != null);

        return repos;
    }

    // Repo별 latestTag 계산
    private RepoLatestTagResult computeLatestTagForRepo(EcrClient ecr, String accountId, String repositoryName) {
        LatestScanResult scan = scanLatestTaggedImage(ecr, accountId, repositoryName);

        return switch (scan.status()) {
            case NO_TAGGED_IMAGES -> new RepoLatestTagResult(null, null, false, "NO_IMAGES_IN_REPOSITORY");

            case TAGGED_BUT_NO_PUSHED_AT -> {
                log.warn("TAGGED exists but pushedAt not available. repo={}", repositoryName);
                yield new RepoLatestTagResult(null, null, false, "TAGGED_EXISTS_BUT_PUSHED_AT_UNKNOWN");
            }

            case TIMED_OUT_OR_LIMITED -> {
                log.warn("Repo scan timed out/limited. repo={}", repositoryName);
                yield new RepoLatestTagResult(null, null, false, "SCAN_TIMED_OUT_OR_LIMITED");
            }

            case OK -> {
                String tag = pickTagForRepoAddr(scan.bestTags());
                if (StringUtils.isBlank(tag)) {
                    yield new RepoLatestTagResult(null, scan.bestPushedAt(), false, "LATEST_IMAGE_HAS_NO_TAGS");
                }
                yield new RepoLatestTagResult(tag, scan.bestPushedAt(), true, null);
            }
        };
    }

    // TAGGED 이미지 중 pushedAt 최신 탐색
    private LatestScanResult scanLatestTaggedImage(EcrClient ecr, String accountId, String repositoryName) {
        Instant startedAt = Instant.now();
        String nextToken = null;
        int iterations = 0;
        int pages = 0;

        boolean sawTagged = false;

        Instant bestPushedAt = null;
        List<String> bestTags = List.of();

        do {
            // 시간 budget
            if (Duration.between(startedAt, Instant.now()).compareTo(REPO_SCAN_BUDGET) > 0) {
                return new LatestScanResult(ScanStatus.TIMED_OUT_OR_LIMITED, bestPushedAt, bestTags);
            }
            // 반복 budget
            if ((iterations + 1) > MAX_PAGINATION_ITERATIONS || (pages + 1) > MAX_PAGES_PER_REPO) {
                return new LatestScanResult(ScanStatus.TIMED_OUT_OR_LIMITED, bestPushedAt, bestTags);
            }

            iterations++;
            pages++;

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
                sawTagged = true;

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

            // nextToken 반복 방지
            if (nextToken != null && nextToken.equals(prev)) {
                return new LatestScanResult(ScanStatus.TIMED_OUT_OR_LIMITED, bestPushedAt, bestTags);
            }

        } while (nextToken != null);

        // 결과 분기
        if (bestPushedAt == null) {
            if (sawTagged) return new LatestScanResult(ScanStatus.TAGGED_BUT_NO_PUSHED_AT, null, List.of());
            return new LatestScanResult(ScanStatus.NO_TAGGED_IMAGES, null, List.of());
        }

        return new LatestScanResult(ScanStatus.OK, bestPushedAt, bestTags);
    }

    // tag 선택
    private String pickTagForRepoAddr(List<String> tags) {
        if (tags == null || tags.isEmpty()) return null;
        if (tags.contains(TAG_LATEST)) return TAG_LATEST;
        return tags.get(0);
    }

    private String normalizeToken(String token) {
        return StringUtils.trimToNull(token);
    }

    private enum ScanStatus {
        OK,
        NO_TAGGED_IMAGES,
        TAGGED_BUT_NO_PUSHED_AT,
        TIMED_OUT_OR_LIMITED
    }

    private record LatestScanResult(ScanStatus status, Instant bestPushedAt, List<String> bestTags) {
        LatestScanResult {
            Objects.requireNonNull(status, "status");
            bestTags = (bestTags == null) ? List.of() : List.copyOf(bestTags); // 불변
        }
    }

    private record RepoLatestTagResult(
            String latestTag,
            Instant lastPushedAt,
            boolean pullable,
            String reason
    ) {
    }
}
