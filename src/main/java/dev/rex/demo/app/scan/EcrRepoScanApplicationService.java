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
 * Repository 스캔 서비스
 * <p>
 * 역할:
 * - 레지스트리의 모든 리포지토리 조회
 * - 각 리포지토리에서 "latest" 기준 또는 최신으로 간주되는 태그 계산
 * - pullable 여부 및 이유(reason) 산출
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcrRepoScanApplicationService {

    private static final String TAG_LATEST = "latest";

    // 무한 루프 방어(토큰 반복/SDK 이상 동작/서버 오류 시 영원히 돌지 않도록 차단)
    private static final int MAX_PAGINATION_ITERATIONS = 10_000;
    private static final int MAX_PAGES_PER_REPO = 2_000;

    // Repo 스캔 budget(리포지토리 하나를 너무 오래 스캔하지 않도록 시간 제한)
    private static final Duration REPO_SCAN_BUDGET = Duration.ofSeconds(15);

    // 페이지 크기(클수록 API 호출 횟수는 줄지만, 응답이 커지고 시간도 늘 수 있음)
    private static final int DESCRIBE_IMAGES_MAX_RESULTS = 500;
    private static final int DESCRIBE_REPOSITORIES_MAX_RESULTS = 1000;

    private final EcrClientFactory ecrClientFactory;

    /**
     * 모든 리포지토리를 스캔하여 latest 태그 정보를 수집
     *
     * @param req 스캔 요청
     * @return EcrRepoScanResponse 리포지토리 목록 및 각 항목의 latest 정보
     */
    public EcrRepoScanResponse scanAllReposWithLatestTag(EcrRepoScanRequest req) {
        // 0) 요청 검증(현재 DTO가 validate()를 제공하므로 그대로 사용)
        req.validate(); // 수동 검증

        // 1) ECR 클라이언트 생성(try-with-resources로 안전하게 close)
        try (EcrClient ecr = ecrClientFactory.create(req.region(), req.accessKeyId(), req.secretAccessKey(), null)) {

            // 2) 레지스트리(accountId)에 속한 모든 repository 조회(페이지네이션 처리)
            List<Repository> repos = listAllRepositories(ecr, req.accountId());

            // 3) repo별로 latest(또는 최신으로 간주되는) 태그 계산
            //    - 각 repo 스캔은 budget/time-limit가 걸려 있어 전체 요청이 무한 지연되지 않도록 함
            List<EcrRepoItem> items = new ArrayList<>(repos.size());
            for (Repository r : repos) {
                // repo 단위 결과(태그/시간/pullable/reason) 계산
                RepoLatestTagResult t = computeLatestTagForRepo(ecr, req.accountId(), r.repositoryName());

                // API 응답 DTO로 변환
                items.add(new EcrRepoItem(
                        r.repositoryName(),
                        r.repositoryUri(),
                        t.latestTag(),
                        t.lastPushedAt(),
                        t.pullable(),
                        t.reason()
                ));
            }

            // 4) 최종 응답: 총 개수 + 리스트
            return new EcrRepoScanResponse(items.size(), items);
        }
    }

    /**
     * 단일 리포지토리를 스캔하여 latest 태그 정보를 반환
     *
     * @param req            스캔 요청
     * @param repositoryName 대상 리포지토리 이름
     * @return EcrRepoItem 단일 리포지토리의 latest 정보
     */
    public EcrRepoItem scanOneRepoWithLatestTag(EcrRepoScanRequest req, String repositoryName) {
        // 0) 요청 검증
        req.validate(); // 수동 검증

        // 1) repositoryName 정규화 및 필수 체크
        String repo = StringUtils.trimToNull(repositoryName);
        if (repo == null) {
            // 컨트롤러/상위 레이어에서 ApiException 정책이 있다면 그에 맞춰 바꿀 수 있음
            throw new IllegalArgumentException("repositoryName은 필수입니다.");
        }

        // 2) ECR 클라이언트 생성
        try (EcrClient ecr = ecrClientFactory.create(req.region(), req.accessKeyId(), req.secretAccessKey(), null)) {

            // 3) 리포지토리 존재 확인(권한/미존재/오타를 빠르게 분기)
            DescribeRepositoriesResponse r = ecr.describeRepositories(
                    DescribeRepositoriesRequest.builder()
                            .registryId(req.accountId())
                            .repositoryNames(repo)
                            .build()
            );

            // 4) 미존재면 "pullable=false + reason"으로 반환(예외로 터뜨리지 않고 상태로 표현)
            if (r.repositories() == null || r.repositories().isEmpty()) {
                return new EcrRepoItem(repo, null, null, null, false, "REPOSITORY_NOT_FOUND");
            }

            // 5) 존재하는 경우 latest 태그 계산 수행
            Repository rr = r.repositories().get(0);
            RepoLatestTagResult t = computeLatestTagForRepo(ecr, req.accountId(), rr.repositoryName());

            // 6) 결과를 DTO로 반환
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

    /**
     * 레지스트리의 모든 리포지토리를 페이지네이션을 사용해 조회
     *
     * @param ecr       ECR 클라이언트
     * @param accountId 레지스트리(계정) ID
     * @return 조회된 Repository 목록
     */
    private List<Repository> listAllRepositories(EcrClient ecr, String accountId) {
        // 결과 누적 리스트
        List<Repository> repos = new ArrayList<>();

        // nextToken 기반 페이지네이션
        String nextToken = null;

        // 토큰 반복/SDK 이상 동작 대비 반복 횟수 제한
        int iterations = 0;

        do {
            // 1) 무한 루프 방지(예: nextToken이 계속 같은 값으로 돌아오는 경우)
            iterations++;
            if (iterations > MAX_PAGINATION_ITERATIONS) {
                log.warn("describeRepositories iterations limit exceeded. accountId={}, iterations={}", accountId, iterations);
                break;
            }

            // 2) 페이지 조회 호출
            DescribeRepositoriesResponse resp = ecr.describeRepositories(
                    DescribeRepositoriesRequest.builder()
                            .registryId(accountId)
                            .maxResults(DESCRIBE_REPOSITORIES_MAX_RESULTS)
                            .nextToken(nextToken)
                            .build()
            );

            // 3) 응답 결과 누적(null 안전)
            if (resp.repositories() != null) {
                repos.addAll(resp.repositories());
            }

            // 4) nextToken 갱신(공백/빈 문자열은 null로 정규화)
            String prev = normalizeToken(nextToken);
            nextToken = normalizeToken(resp.nextToken());

            // 5) nextToken 반복 방지(이상 응답 방어)
            if (nextToken != null && nextToken.equals(prev)) {
                log.warn("describeRepositories nextToken repeated. accountId={}, token={}", accountId, nextToken);
                break;
            }

        } while (nextToken != null);

        return repos;
    }

    /**
     * 특정 리포지토리에서 최신 pushedAt 기준 태그를 계산
     *
     * @param ecr            ECR 클라이언트
     * @param accountId      레지스트리(계정) ID
     * @param repositoryName 리포지토리 이름
     * @return RepoLatestTagResult 계산 결과 (태그, 시각, pullable, reason)
     */
    private RepoLatestTagResult computeLatestTagForRepo(EcrClient ecr, String accountId, String repositoryName) {
        // 1) TAGGED 이미지를 대상으로 “가장 최근 pushedAt”을 찾는 스캔 수행
        //    - 시간/페이지 budget을 초과하면 TIMED_OUT_OR_LIMITED로 종료
        LatestScanResult scan = scanLatestTaggedImage(ecr, accountId, repositoryName);

        // 2) 스캔 결과를 상태별로 해석하여 최종 DTO로 변환
        //    - pullable: 실제로 repositoryUri:tag 형태로 pull 가능한지 여부
        //    - reason: pullable=false인 경우 사유 코드(모니터링/디버깅/프론트 표시용)
        return switch (scan.status()) {
            case NO_TAGGED_IMAGES -> new RepoLatestTagResult(
                    null,
                    null,
                    false,
                    "NO_IMAGES_IN_REPOSITORY"
            );

            case TAGGED_BUT_NO_PUSHED_AT -> {
                // TAGGED 이미지는 있었는데 pushedAt이 없는 경우(드문 케이스)
                log.warn("TAGGED exists but pushedAt not available. repo={}", repositoryName);
                yield new RepoLatestTagResult(
                        null,
                        null,
                        false,
                        "TAGGED_EXISTS_BUT_PUSHED_AT_UNKNOWN"
                );
            }

            case TIMED_OUT_OR_LIMITED -> {
                // budget 초과/반복 제한 도달: 결과 신뢰도가 낮으므로 reason으로 반환
                log.warn("Repo scan timed out/limited. repo={}", repositoryName);
                yield new RepoLatestTagResult(
                        null,
                        null,
                        false,
                        "SCAN_TIMED_OUT_OR_LIMITED"
                );
            }

            case OK -> {
                // 3) 가장 최근 pushedAt 이미지의 태그들 중 “repo 주소로 사용할 태그” 선택
                //    - tags에 latest가 있으면 latest를 우선, 없으면 첫 번째 태그
                String tag = pickTagForRepoAddr(scan.bestTags());

                // 4) 최신 이미지가 태그가 없는 경우(태그 해제/특수 상태) 방어
                if (StringUtils.isBlank(tag)) {
                    yield new RepoLatestTagResult(
                            null,
                            scan.bestPushedAt(),
                            false,
                            "LATEST_IMAGE_HAS_NO_TAGS"
                    );
                }

                // 5) 정상: pull 가능
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
     * TAGGED 상태의 이미지들 중 pushedAt이 최신인 것을 찾음
     * 시간/페이지 예산을 초과하면 TIMED_OUT_OR_LIMITED 상태를 반환
     *
     * @param ecr            ECR 클라이언트
     * @param accountId      레지스트리(계정) ID
     * @param repositoryName 리포지토리 이름
     * @return LatestScanResult 스캔 결과(상태, 최종 pushedAt, 해당 태그 목록)
     */
    private LatestScanResult scanLatestTaggedImage(EcrClient ecr, String accountId, String repositoryName) {
        // 1) 스캔 시작 시각(시간 budget 계산 기준)
        Instant startedAt = Instant.now();

        // 2) 이미지 페이지네이션 토큰
        String nextToken = null;

        // 3) 무한 루프 방지용 카운터(토큰 반복/비정상 응답 대비)
        int iterations = 0;

        // 4) repo별 페이지 수 제한(특정 repo에 이미지가 너무 많을 때 서버 부담/응답 지연 방지)
        int pages = 0;

        // 5) TAGGED 이미지를 한 번이라도 봤는지(결과 분기 판단용)
        boolean sawTagged = false;

        // 6) “최신 pushedAt” 후보 값과 그 이미지의 tags
        Instant bestPushedAt = null;
        List<String> bestTags = List.of();

        do {
            // A) 시간 budget 체크: 너무 오래 스캔하지 않고 제한 초과 시 중단
            if (Duration.between(startedAt, Instant.now()).compareTo(REPO_SCAN_BUDGET) > 0) {
                return new LatestScanResult(ScanStatus.TIMED_OUT_OR_LIMITED, bestPushedAt, bestTags);
            }

            // B) 반복/페이지 budget 체크: 토큰 반복/과도한 데이터로 무한 호출 방지
            if ((iterations + 1) > MAX_PAGINATION_ITERATIONS || (pages + 1) > MAX_PAGES_PER_REPO) {
                return new LatestScanResult(ScanStatus.TIMED_OUT_OR_LIMITED, bestPushedAt, bestTags);
            }

            iterations++;
            pages++;

            // C) TAGGED 이미지만 조회
            //    - UNTAGGED(태그 없는) 이미지는 “repo:tag” 형태로 pull 불가이므로 제외
            DescribeImagesResponse resp = ecr.describeImages(
                    DescribeImagesRequest.builder()
                            .registryId(accountId)
                            .repositoryName(repositoryName)
                            .filter(f -> f.tagStatus(TagStatus.TAGGED))
                            .maxResults(DESCRIBE_IMAGES_MAX_RESULTS)
                            .nextToken(nextToken)
                            .build()
            );

            // D) 응답의 imageDetails를 순회하며 bestPushedAt 갱신
            List<ImageDetail> details = resp.imageDetails();
            if (details != null && !details.isEmpty()) {
                sawTagged = true;

                for (ImageDetail d : details) {
                    // pushedAt이 없는 엔트리는 비교 대상에서 제외
                    Instant pushedAt = d.imagePushedAt();
                    if (pushedAt == null) continue;

                    // 최신 pushedAt 발견 시 best 갱신 + 해당 태그 목록 저장
                    if (bestPushedAt == null || pushedAt.isAfter(bestPushedAt)) {
                        bestPushedAt = pushedAt;
                        bestTags = (d.imageTags() == null) ? List.of() : d.imageTags();
                    }
                }
            }

            // E) nextToken 갱신(공백은 null로 정규화)
            String prev = normalizeToken(nextToken);
            nextToken = normalizeToken(resp.nextToken());

            // F) nextToken 반복 방지(비정상 응답/SDK 이슈/서비스 문제 대비)
            if (nextToken != null && nextToken.equals(prev)) {
                return new LatestScanResult(ScanStatus.TIMED_OUT_OR_LIMITED, bestPushedAt, bestTags);
            }

        } while (nextToken != null);

        // G) 스캔 종료 후 결과 분기
        //    - bestPushedAt이 없으면: (1) TAGGED는 있었는데 pushedAt이 없음, 또는 (2) TAGGED 자체가 없음
        if (bestPushedAt == null) {
            if (sawTagged) {
                return new LatestScanResult(ScanStatus.TAGGED_BUT_NO_PUSHED_AT, null, List.of());
            }
            return new LatestScanResult(ScanStatus.NO_TAGGED_IMAGES, null, List.of());
        }

        // H) 정상 결과: 최신 pushedAt + 해당 태그 목록 반환
        return new LatestScanResult(ScanStatus.OK, bestPushedAt, bestTags);
    }

    /**
     * 리포지토리 주소용 태그 선택 로직
     * - tags에 "latest"가 있으면 우선 선택, 아니면 첫 번째 태그 반환
     *
     * @param tags 태그 목록
     * @return 선택된 태그 또는 null
     */
    private String pickTagForRepoAddr(List<String> tags) {
        // null/empty 방어: 태그가 없으면 repo:tag 형태로 pull 불가
        if (tags == null || tags.isEmpty()) return null;

        // "latest"가 있으면 관례적으로 가장 대표 태그이므로 우선 선택
        if (tags.contains(TAG_LATEST)) return TAG_LATEST;

        // 그렇지 않으면 첫 번째 태그(정렬/우선순위 정책이 필요하면 여기서 확장)
        return tags.get(0);
    }

    /**
     * 토큰을 정규화하여 공백/빈 문자열을 null로 변환
     *
     * @param token 토큰 문자열
     * @return 정규화된 토큰 또는 null
     */
    private String normalizeToken(String token) {
        // AWS SDK nextToken은 공백이 섞일 수 있으므로 trim 후 null 처리
        return StringUtils.trimToNull(token);
    }

    /**
     * 스캔 상태 열거형
     */
    private enum ScanStatus {
        OK,
        NO_TAGGED_IMAGES,
        TAGGED_BUT_NO_PUSHED_AT,
        TIMED_OUT_OR_LIMITED
    }

    /**
     * 최신 스캔 결과를 담는 내부 레코드
     * <p>
     * 불변(bestTags 복사)로 보관
     */
    private record LatestScanResult(ScanStatus status, Instant bestPushedAt, List<String> bestTags) {
        LatestScanResult {
            // status는 상태 머신이므로 null이면 의미가 없어서 즉시 실패
            Objects.requireNonNull(status, "status");

            // bestTags는 외부 리스트 참조를 그대로 들고 있지 않도록 방어적 복사
            // - 이후 호출자가 리스트를 수정해도 내부 상태가 변하지 않음(불변성)
            bestTags = (bestTags == null) ? List.of() : List.copyOf(bestTags);
        }
    }

    /**
     * 리포지토리별 최종 결과 DTO
     */
    private record RepoLatestTagResult(
            String latestTag,
            Instant lastPushedAt,
            boolean pullable,
            String reason
    ) {
    }
}
