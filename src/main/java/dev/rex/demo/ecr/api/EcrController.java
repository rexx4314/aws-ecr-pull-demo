package dev.rex.demo.ecr.api;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.common.util.Masking;
import dev.rex.demo.ecr.api.dto.*;
import dev.rex.demo.ecr.download.DownloadResult;
import dev.rex.demo.ecr.service.EcrManifestService;
import dev.rex.demo.ecr.service.EcrRepoScanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ecr")
public class EcrController {

    // 스캔 캐시 TTL(짧게 유지)
    private static final Duration SCAN_CACHE_TTL = Duration.ofMinutes(2);

    private final EcrRepoScanService repoScanService;
    private final EcrManifestService manifestService;

    // 스캔 결과 캐시(Repo 단위)
    private final ConcurrentHashMap<String, CacheEntry> scanCache = new ConcurrentHashMap<>();

    /**
     * Repo 전체 스캔
     * - @Valid: Bean Validation
     * - req.validate(): 수동 검증
     */
    @PostMapping(value = "/scan", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public EcrRepoScanResponse scanRepos(@Valid @RequestBody EcrRepoScanRequest req) {
        req.validate(); // 수동 검증

        log.info("ECR scan requested. region={}, accountId={}, accessKeyId={}",
                req.region(), req.accountId(), Masking.maskAccessKeyId(req.accessKeyId()));

        EcrRepoScanResponse resp = repoScanService.scanAllReposWithLatestTag(req);

        // 캐시 적재(Repo 단위)
        putScanCache(req, resp.items());

        return resp;
    }

    /**
     * Layer 스트리밍 다운로드
     * - pullable 검증(캐시 우선)
     * - 최신 tag 정책 검증
     */
    @PostMapping(value = "/download", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public EcrDownloadResponse download(@Valid @RequestBody EcrDownloadRequest req) {

        String imageRef = StringUtils.isNotBlank(req.tag()) ? req.tag() : req.digest();
        log.info("ECR download requested. region={}, accountId={}, repo={}, imageRef(tag/digest)={}, accessKeyId={}, resolveLatest={}",
                req.region(),
                req.accountId(),
                req.repositoryName(),
                StringUtils.defaultString(imageRef, "(null)"),
                Masking.maskAccessKeyId(req.accessKeyId()),
                req.resolveLatest()
        );

        // 1) pullable 검증(캐시 우선, 없으면 단일 repo 스캔)
        EcrRepoItem repoStatus = getOrScanOneRepo(req);

        if (repoStatus == null || !repoStatus.pullable()) {
            String reason = (repoStatus == null) ? "REPO_STATUS_NULL" : safe(repoStatus.reason());
            throw new ApiException(
                    mapReasonToErrorCode(reason),
                    "Repository not pullable: " + reason,
                    Map.of("repositoryName", safe(req.repositoryName()), "reason", reason)
            );
        }

        // 2) 최신 tag만 허용
        enforceLatestTagPolicyIfNeeded(req, repoStatus);

        // 3) 실제 다운로드(Manifest + Layers)
        DownloadResult r = manifestService.downloadByEcrApi(req);

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

    // 캐시 적재(Repo 단위)
    private void putScanCache(EcrRepoScanRequest req, List<EcrRepoItem> items) {
        Instant expiresAt = Instant.now().plus(SCAN_CACHE_TTL);
        if (items == null || items.isEmpty()) return;

        for (EcrRepoItem it : items) {
            if (it == null || StringUtils.isBlank(it.repositoryName())) continue;
            String key = cacheKey(req.region(), req.accountId(), it.repositoryName());
            scanCache.put(key, new CacheEntry(it, expiresAt));
        }
    }

    // 캐시 조회 또는 단일 repo 스캔
    private EcrRepoItem getOrScanOneRepo(EcrDownloadRequest req) {
        String key = cacheKey(req.region(), req.accountId(), req.repositoryName());

        CacheEntry cached = scanCache.get(key);
        if (cached != null && !cached.isExpired()) return cached.item();

        // 캐시 미스: 단일 repo 스캔
        EcrRepoScanRequest scanReq = new EcrRepoScanRequest(
                req.region(),
                req.accountId(),
                req.accessKeyId(),
                req.secretAccessKey()
        );
        scanReq.validate(); // 수동 검증

        EcrRepoItem one = repoScanService.scanOneRepoWithLatestTag(scanReq, req.repositoryName());
        scanCache.put(key, new CacheEntry(one, Instant.now().plus(SCAN_CACHE_TTL)));

        return one;
    }

    // 최신 tag 정책 강제
    private void enforceLatestTagPolicyIfNeeded(EcrDownloadRequest req, EcrRepoItem repoStatus) {
        if (req.resolveLatest()) return;

        // digest 요청이면 tag 정책과 무관
        if (StringUtils.isBlank(req.tag())) return;

        String latestTag = StringUtils.trimToNull(repoStatus.latestTag());
        String requestedTag = StringUtils.trimToNull(req.tag());

        // latestTag가 null이면 정책 검사 불가(통과)
        if (latestTag == null || requestedTag == null) return;

        if (!latestTag.equals(requestedTag)) {
            throw new ApiException(
                    ErrorCode.TAG_NOT_LATEST,
                    "Requested tag is not the latest tag.",
                    Map.of(
                            "repositoryName", safe(req.repositoryName()),
                            "requestedTag", requestedTag,
                            "latestTag", latestTag
                    )
            );
        }
    }

    private String cacheKey(String region, String accountId, String repo) {
        return safe(region) + "|" + safe(accountId) + "|" + safe(repo);
    }

    private String safe(String s) {
        String t = StringUtils.trimToNull(s);
        return (t == null) ? "" : t;
    }

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

    private record CacheEntry(EcrRepoItem item, Instant expiresAt) {
        boolean isExpired() {
            return expiresAt == null || Instant.now().isAfter(expiresAt);
        }
    }
}
