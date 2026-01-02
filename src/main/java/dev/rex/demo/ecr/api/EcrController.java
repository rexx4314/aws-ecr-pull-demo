package dev.rex.demo.ecr.api;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.common.util.Masking;
import dev.rex.demo.ecr.api.dto.*;
import dev.rex.demo.app.download.DownloadResult;
import dev.rex.demo.app.export.EcrDockerSaveExportResult;
import dev.rex.demo.app.export.DockerSaveExportApplicationService;
import dev.rex.demo.app.download.EcrDownloadApplicationService;
import dev.rex.demo.app.scan.EcrRepoScanApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
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

    private static final Duration SCAN_CACHE_TTL = Duration.ofMinutes(2);

    private final EcrRepoScanApplicationService repoScanService;
    private final EcrDownloadApplicationService manifestService;
    private final DockerSaveExportApplicationService dockerSaveExportService;

    private final ConcurrentHashMap<String, CacheEntry> scanCache = new ConcurrentHashMap<>();

    @PostMapping(value = "/scan", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public EcrRepoScanResponse scanRepos(@Valid @RequestBody EcrRepoScanRequest req) {
        log.info("ECR scan requested. region={}, accountId={}, accessKeyId={}",
                req.region(), req.accountId(), Masking.maskAccessKeyId(req.accessKeyId()));

        EcrRepoScanResponse resp = repoScanService.scanAllReposWithLatestTag(req);
        putScanCache(req, resp.items());
        return resp;
    }

    @PostMapping(value = "/download", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public EcrDownloadResponse download(@Valid @RequestBody EcrDownloadRequest req) {

        String tagOrDigest = (StringUtils.isNotBlank(req.tag()) ? req.tag() : req.digest());
        log.info("ECR download requested. region={}, accountId={}, repo={}, imageRef(tag/digest)={}, accessKeyId={}, resolveLatest={}",
                req.region(),
                req.accountId(),
                req.repositoryName(),
                tagOrDigest,
                Masking.maskAccessKeyId(req.accessKeyId()),
                req.resolveLatest()
        );

        EcrRepoItem repoStatus = getOrScanOneRepo(req);

        if (!repoStatus.pullable()) {
            throw new ApiException(
                    mapReasonToErrorCode(repoStatus.reason()),
                    "Repository not pullable: " + safe(repoStatus.reason()),
                    Map.of(
                            "repositoryName", req.repositoryName(),
                            "reason", safe(repoStatus.reason())
                    )
            );
        }

        if (!req.resolveLatest() && StringUtils.isNotBlank(req.tag())) {
            String latestTag = StringUtils.trimToNull(repoStatus.latestTag());
            String requestedTag = StringUtils.trimToNull(req.tag());
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

    @PostMapping(value = "/export/docker-save", consumes = MediaType.APPLICATION_JSON_VALUE, produces = "application/x-tar")
    public ResponseEntity<FileSystemResource> exportDockerSave(@Valid @RequestBody EcrDockerSaveExportRequest req) {

        log.info("ECR export docker-save requested. region={}, accountId={}, repo={}, tag={}, accessKeyId={}, resolveLatest={}",
                req.region(),
                req.accountId(),
                req.repositoryName(),
                StringUtils.defaultString(req.tag(), "(null)"),
                Masking.maskAccessKeyId(req.accessKeyId()),
                req.resolveLatest()
        );

        EcrDockerSaveExportResult r = dockerSaveExportService.exportDockerSaveTarFromLocal(req);
        Path tar = r.tarPath();

        String fileName = tar.getFileName().toString();
        String serverTarPath = tar.toAbsolutePath().toString();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header("X-Server-Tar-Path", serverTarPath)
                .contentType(MediaType.parseMediaType("application/x-tar"))
                .body(new FileSystemResource(tar));
    }

    private void putScanCache(EcrRepoScanRequest req, List<EcrRepoItem> items) {
        Instant expiresAt = Instant.now().plus(SCAN_CACHE_TTL);
        for (EcrRepoItem it : items) {
            if (it == null || StringUtils.isBlank(it.repositoryName())) continue;
            String key = cacheKey(req.region(), req.accountId(), it.repositoryName());
            scanCache.put(key, new CacheEntry(it, expiresAt));
        }
    }

    private EcrRepoItem getOrScanOneRepo(EcrDownloadRequest req) {
        String key = cacheKey(req.region(), req.accountId(), req.repositoryName());
        CacheEntry cached = scanCache.get(key);

        if (cached != null && !cached.isExpired()) {
            return cached.item();
        }

        EcrRepoScanRequest scanReq = new EcrRepoScanRequest(
                req.region(),
                req.accountId(),
                req.accessKeyId(),
                req.secretAccessKey()
        );

        EcrRepoItem one = repoScanService.scanOneRepoWithLatestTag(scanReq, req.repositoryName());
        scanCache.put(key, new CacheEntry(one, Instant.now().plus(SCAN_CACHE_TTL)));
        return one;
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
