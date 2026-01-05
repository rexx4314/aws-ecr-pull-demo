package dev.rex.demo.ecr.api;

import dev.rex.demo.app.download.DownloadResult;
import dev.rex.demo.app.download.EcrDownloadApplicationService;
import dev.rex.demo.app.export.DockerSaveExportApplicationService;
import dev.rex.demo.app.export.EcrDockerSaveExportResult;
import dev.rex.demo.app.scan.EcrRepoScanApplicationService;
import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.common.util.Masking;
import dev.rex.demo.ecr.api.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ECR API Controller
 *
 * <p>
 * 역할:
 * - /scan : 레지스트리의 리포지토리들을 스캔하고 latest 관련 정보를 반환
 * - /download : 다운로드 수행 전 "pullable" 여부 및 tag 최신성(정책)을 검증한 후 다운로드 오케스트레이션 호출
 * - /export/docker-save : 로컬 산출물 기반으로 docker-save tar 생성 후 파일 다운로드 응답 반환
 *
 * <p>
 * 설계 포인트(Clean Code):
 * - Controller는 "흐름/정책 검증/응답 구성"만 담당
 * - 복잡한 로직은 private 메서드로 분리(의도 드러내기)
 * - 로그/마스킹/예외코드 매핑 정책을 명확히 고정
 * - 스캔 결과는 TTL 캐시로 재사용(다운로드 전 사전검증 비용 절감)
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ecr")
public class EcrController {

    /**
     * scan 응답 캐시 TTL
     * - 다운로드 요청이 연속으로 들어올 때 /scan 비용을 줄이기 위해 사용
     * - 너무 길면 최신성 문제, 너무 짧으면 캐시 효과 감소 → 현재 2분은 타협값
     */
    private static final Duration SCAN_CACHE_TTL = Duration.ofMinutes(2);

    /**
     * export endpoint의 응답 MIME
     * - Spring MediaType에 "tar"가 기본 제공되지 않으므로 명시 문자열로 고정
     */
    private static final String TAR_MIME = "application/x-tar";

    private final EcrRepoScanApplicationService repoScanService;
    private final EcrDownloadApplicationService manifestService;
    private final DockerSaveExportApplicationService dockerSaveExportService;

    /**
     * (region, accountId, repositoryName) 기준 스캔 결과 캐시
     * - 멀티 인스턴스 환경이면 로컬 캐시이므로 "인스턴스 단위" 캐시임
     * - 현재 구조는 단일 인스턴스/개발/간단 운영에 적합
     */
    private final ConcurrentHashMap<String, CacheEntry> scanCache = new ConcurrentHashMap<>();

    /**
     * 레지스트리 전체 리포지토리 스캔
     *
     * @param req 스캔 요청
     * @return 스캔 결과(리포지토리별 latest/tag/pullable/reason 포함)
     */
    @PostMapping(value = "/scan", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public EcrRepoScanResponse scanRepos(@Valid @RequestBody EcrRepoScanRequest req) {
        // 1) 요청 로깅(민감정보 마스킹)
        log.info("ECR scan requested. region={}, accountId={}, accessKeyId={}",
                req.region(), req.accountId(), Masking.maskAccessKeyId(req.accessKeyId()));

        // 2) 스캔 실행(서비스 레이어로 위임)
        EcrRepoScanResponse resp = repoScanService.scanAllReposWithLatestTag(req);

        // 3) 스캔 결과를 캐시에 반영(다운로드 요청의 pre-check 최적화)
        putScanCache(req, resp.items());

        // 4) 결과 반환
        return resp;
    }

    /**
     * ECR 이미지 다운로드
     *
     * <p>
     * 정책/검증:
     * - download 수행 전 repo pullable 여부 확인(캐시 기반 or 단건 스캔)
     * - resolveLatest=false + tag 지정 시 "요청 tag가 latest인지" 정책 검증
     *
     * @param req 다운로드 요청
     * @return 다운로드 결과(로컬 경로 포함)
     */
    @PostMapping(value = "/download", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public EcrDownloadResponse download(@Valid @RequestBody EcrDownloadRequest req) {

        // 1) 로그에 표시할 이미지 참조(태그/다이제스트)
        String tagOrDigest = resolveTagOrDigest(req);

        // 2) 요청 로깅(민감정보 마스킹)
        log.info("ECR download requested. region={}, accountId={}, repo={}, imageRef(tag/digest)={}, accessKeyId={}, resolveLatest={}",
                req.region(),
                req.accountId(),
                req.repositoryName(),
                tagOrDigest,
                Masking.maskAccessKeyId(req.accessKeyId()),
                req.resolveLatest()
        );

        // 3) repo 상태 확인(캐시 hit면 재사용, miss면 단건 스캔)
        EcrRepoItem repoStatus = getOrScanOneRepo(req);

        // 4) pullable 검증: pull 불가면 즉시 실패(다운로드 비용 발생 전 차단)
        ensureRepoPullable(req, repoStatus);

        // 5) resolveLatest=false + tag 요청이면 tag 최신성 정책 적용
        enforceTagLatestPolicyIfNeeded(req, repoStatus);

        // 6) 다운로드 오케스트레이션(서비스 레이어로 위임)
        DownloadResult r = manifestService.downloadByEcrApi(req);

        // 7) 컨트롤러 응답 DTO로 변환
        return new EcrDownloadResponse(
                r.resolvedTag(),
                r.resolvedDigest(),
                r.layerCount(),
                r.downloadedCount(),
                r.outputPath(),
                r.manifestPath(),
                r.configPath(),
                r.message()
        );
    }

    /**
     * docker-save tar export
     *
     * <p>
     * 특징:
     * - export는 "로컬 산출물 기반" 정책(서비스에서 검증)
     * - 응답은 tar 파일 다운로드 형태
     * - Content-Disposition + 서버 내부 경로(X-Server-Tar-Path) 헤더 제공
     *
     * @param req export 요청
     * @return tar 파일을 body로 갖는 ResponseEntity
     */
    @PostMapping(value = "/export/docker-save", consumes = MediaType.APPLICATION_JSON_VALUE, produces = TAR_MIME)
    public ResponseEntity<FileSystemResource> exportDockerSave(@Valid @RequestBody EcrDockerSaveExportRequest req) {

        // 1) 요청 로깅(민감정보 마스킹)
        log.info("ECR export docker-save requested. region={}, accountId={}, repo={}, tag={}, accessKeyId={}, resolveLatest={}",
                req.region(),
                req.accountId(),
                req.repositoryName(),
                Objects.toString(req.tag(), "(null)"),
                Masking.maskAccessKeyId(req.accessKeyId()),
                req.resolveLatest()
        );

        // 2) export 실행(서비스에서 로컬 산출물 검증 + tar 생성)
        EcrDockerSaveExportResult r = dockerSaveExportService.exportDockerSaveTarFromLocal(req);

        // 3) 생성된 tar 경로
        Path tar = r.tarPath();

        // 4) 응답 파일명/서버 내부 경로
        //    - 파일명은 클라이언트 다운로드명으로 사용
        //    - server path는 디버깅/운영 확인용(보안 고려 필요 시 제거/마스킹 권장)
        String fileName = tar.getFileName().toString();
        String serverTarPath = tar.toAbsolutePath().toString();

        // 5) 파일 다운로드 응답 구성
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header("X-Server-Tar-Path", serverTarPath)
                .contentType(MediaType.parseMediaType(TAR_MIME))
                .body(new FileSystemResource(tar));
    }

    /**
     * scan 결과를 캐시에 넣음
     *
     * <p>
     * 키: region|accountId|repo
     * 값: EcrRepoItem + expiresAt
     *
     * @param req   요청(키 생성에 사용)
     * @param items 스캔 결과 목록
     */
    private void putScanCache(EcrRepoScanRequest req, List<EcrRepoItem> items) {
        // 1) 캐시 만료 시각(항목 단위 동일 TTL)
        Instant expiresAt = Instant.now().plus(SCAN_CACHE_TTL);

        // 2) 스캔 결과를 repo 단위로 캐시에 저장
        //    - null/blank 방어
        for (EcrRepoItem it : items) {
            if (it == null || StringUtils.isBlank(it.repositoryName())) continue;

            String key = cacheKey(req.region(), req.accountId(), it.repositoryName());
            scanCache.put(key, new CacheEntry(it, expiresAt));
        }
    }

    /**
     * 다운로드 요청에서 repo 상태를 가져옴
     *
     * <p>
     * 동작:
     * - 캐시에 있고 만료되지 않았으면 캐시 반환
     * - 없거나 만료되었으면 단건 스캔 후 캐시 갱신
     *
     * @param req 다운로드 요청
     * @return EcrRepoItem(repo 상태)
     */
    private EcrRepoItem getOrScanOneRepo(EcrDownloadRequest req) {
        // 1) 캐시 키 생성
        String key = cacheKey(req.region(), req.accountId(), req.repositoryName());

        // 2) 캐시 조회
        CacheEntry cached = scanCache.get(key);

        // 3) 캐시 hit + 유효하면 그대로 사용(서비스 호출 비용 절감)
        if (cached != null && !cached.isExpired()) {
            return cached.item();
        }

        // 4) 캐시 miss/expired → 단건 스캔 수행
        //    - 스캔 요청 DTO는 download 요청의 credential/region/accountId를 재사용
        EcrRepoScanRequest scanReq = new EcrRepoScanRequest(
                req.region(),
                req.accountId(),
                req.accessKeyId(),
                req.secretAccessKey()
        );

        // 5) 단건 스캔 실행
        EcrRepoItem one = repoScanService.scanOneRepoWithLatestTag(scanReq, req.repositoryName());

        // 6) 캐시 갱신
        scanCache.put(key, new CacheEntry(one, Instant.now().plus(SCAN_CACHE_TTL)));

        return one;
    }

    /**
     * repo pullable 여부를 검증
     * - pullable=false면 ApiException으로 즉시 실패(다운로드 실행 전 차단)
     */
    private void ensureRepoPullable(EcrDownloadRequest req, EcrRepoItem repoStatus) {
        // 1) pullable=false면 reason에 따라 ErrorCode를 매핑
        if (!repoStatus.pullable()) {
            ErrorCode mapped = mapReasonToErrorCode(repoStatus.reason());

            // 2) 사용자에게 최소한의 진단 정보 제공(민감정보 제외)
            throw new ApiException(
                    mapped,
                    "Repository not pullable: " + safe(repoStatus.reason()),
                    Map.of(
                            "repositoryName", req.repositoryName(),
                            "reason", safe(repoStatus.reason())
                    )
            );
        }
    }

    /**
     * tag 최신성 정책 적용(필요한 경우에만)
     *
     * <p>
     * 정책:
     * - resolveLatest=false + tag 지정 요청인 경우,
     * 스캔 결과의 latestTag와 요청 tag가 다르면 차단
     *
     * <p>
     * 주의:
     * - latestTag가 null이면 비교 불가 → 여기서는 차단하지 않음(상위 서비스에서 실제 download 실패 가능)
     * - resolveLatest=true인 경우: "무조건 최신 선택"이므로 tag 비교 자체가 무의미
     */
    private void enforceTagLatestPolicyIfNeeded(EcrDownloadRequest req, EcrRepoItem repoStatus) {
        // 1) resolveLatest=true면 정책 적용 대상 아님
        if (req.resolveLatest()) return;

        // 2) tag 요청이 없으면 정책 적용 대상 아님(digest 기반이거나 태그 미지정)
        if (StringUtils.isBlank(req.tag())) return;

        // 3) latestTag vs requestedTag 비교(공백 제거)
        String latestTag = StringUtils.trimToNull(repoStatus.latestTag());
        String requestedTag = StringUtils.trimToNull(req.tag());

        // 4) 둘 다 있을 때만 비교하고, 다르면 차단
        if (latestTag != null && requestedTag != null && !latestTag.equals(requestedTag)) {
            throw new ApiException(
                    ErrorCode.TAG_NOT_LATEST,
                    "Requested tag is not the latest tag.",
                    Map.of(
                            "repositoryName", req.repositoryName(),
                            "requestedTag", requestedTag,
                            "latestTag", latestTag
                    )
            );
        }
    }

    /**
     * 요청에서 로그용 tag/digest를 결정
     * - tag가 있으면 tag
     * - 없으면 digest
     *
     * <p>
     * 참고:
     * - @Valid 요청에서 교차검증이 보장된다면(tag/digest 중 하나는 존재)
     * - 그래도 방어적으로 처리(둘 다 blank면 null → safe 처리로 로그 NPE 방지)
     */
    private String resolveTagOrDigest(EcrDownloadRequest req) {
        return StringUtils.isNotBlank(req.tag()) ? req.tag() : req.digest();
    }

    /**
     * 캐시 키 생성
     * - null/blank 방어 후 문자열 결합
     */
    private String cacheKey(String region, String accountId, String repo) {
        return safe(region) + "|" + safe(accountId) + "|" + safe(repo);
    }

    /**
     * 문자열 정규화:
     * - trim 후 null이면 빈 문자열 반환
     *
     * <p>
     * 용도:
     * - cache key 생성 시 null 방지
     * - 로그/메시지에서 null 방지
     */
    private String safe(String s) {
        String t = StringUtils.trimToNull(s);
        return (t == null) ? "" : t;
    }

    /**
     * repo scan reason -> ErrorCode 매핑
     *
     * <p>
     * 설계 의도:
     * - reason은 스캔 서비스에서 문자열로 결정되므로
     * - controller에서 ErrorCode로 변환해 일관된 에러 응답을 만듦
     *
     * <p>
     * 주의:
     * - reason 문자열이 변경되면 매핑도 함께 변경되어야 함
     * - 향후 enum/상수로 승격하는 것이 더 안전(팀 합의 필요)
     */
    private ErrorCode mapReasonToErrorCode(String reason) {
        String r = StringUtils.trimToNull(reason);
        if (r == null) return ErrorCode.REPO_NOT_PULLABLE;

        return switch (r) {
            case "NO_IMAGES_IN_REPOSITORY" -> ErrorCode.NO_IMAGES_IN_REPOSITORY;
            case "TAGGED_EXISTS_BUT_PUSHED_AT_UNKNOWN" -> ErrorCode.TAGGED_EXISTS_BUT_PUSHED_AT_UNKNOWN;
            case "SCAN_TIMED_OUT_OR_LIMITED" -> ErrorCode.SCAN_TIMED_OUT_OR_LIMITED;
            case "LATEST_IMAGE_HAS_NO_TAGS" -> ErrorCode.LATEST_IMAGE_HAS_NO_TAGS;
            case "REPOSITORY_NOT_FOUND" -> ErrorCode.REPOSITORY_NOT_FOUND;
            default -> ErrorCode.REPO_NOT_PULLABLE;
        };
    }

    /**
     * scan cache 엔트리
     *
     * @param item      repo 상태
     * @param expiresAt 만료 시각
     */
    private record CacheEntry(EcrRepoItem item, Instant expiresAt) {

        /**
         * 만료 여부
         * - expiresAt이 null이면 안전하게 만료로 간주
         */
        boolean isExpired() {
            return expiresAt == null || Instant.now().isAfter(expiresAt);
        }
    }
}
