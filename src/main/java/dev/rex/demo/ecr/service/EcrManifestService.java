package dev.rex.demo.ecr.service;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.ecr.api.dto.EcrDownloadRequest;
import dev.rex.demo.ecr.download.DownloadLayout;
import dev.rex.demo.ecr.download.DownloadResult;
import dev.rex.demo.ecr.infrastructure.EcrClientFactory;
import dev.rex.demo.ecr.manifest.ManifestParser;
import dev.rex.demo.ecr.manifest.ParsedManifest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.EcrException;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class EcrManifestService {

    private final EcrClientFactory factory;
    private final ImageRefResolver imageRefResolver;
    private final ManifestFetcher manifestFetcher;
    private final DownloadExecutor downloadExecutor;

    // 기본 저장 경로
    @Value("${app.download.baseDir:./out}")
    private String defaultBaseDir;

    // 동시성 기본/최대
    @Value("${app.download.defaultConcurrency:4}")
    private int defaultConcurrency;

    @Value("${app.download.maxConcurrency:5}")
    private int maxConcurrency;

    // HTTP 기본 타임아웃
    @Value("${app.download.httpTimeoutSeconds:180}")
    private int defaultHttpTimeoutSeconds;

    // 재시도 기본
    @Value("${app.download.maxRetries:4}")
    private int defaultMaxRetries;

    // 최신 선택 기본 제한
    @Value("${app.scan.defaultMaxPages:50}")
    private int defaultMaxPages;

    @Value("${app.scan.defaultMaxImages:2000}")
    private int defaultMaxImages;

    /**
     * ECR API 기반 다운로드(오케스트레이션)
     */
    public DownloadResult downloadByEcrApi(EcrDownloadRequest req) {
        Objects.requireNonNull(req, "req");

        // 옵션 정규화
        int concurrency = clamp(nvl(req.concurrency(), defaultConcurrency), 1, maxConcurrency);
        int httpTimeoutSeconds = (req.httpTimeoutSeconds() == null || req.httpTimeoutSeconds() <= 0)
                ? defaultHttpTimeoutSeconds : req.httpTimeoutSeconds();
        int maxRetries = (req.maxRetries() == null || req.maxRetries() <= 0)
                ? defaultMaxRetries : req.maxRetries();

        int maxPages = (req.maxPages() == null || req.maxPages() <= 0) ? defaultMaxPages : req.maxPages();
        int maxImages = (req.maxImages() == null || req.maxImages() <= 0) ? defaultMaxImages : req.maxImages();

        boolean verifySha256 = req.verifySha256();
        boolean includeConfig = req.includeConfig();
        String outputDir = StringUtils.defaultIfBlank(req.outputDir(), defaultBaseDir);

        try (EcrClient ecr = factory.create(req.region(), req.accessKeyId(), req.secretAccessKey(), req.sessionToken())) {

            // 1) 이미지 레퍼런스 결정
            ImageRefResolver.ResolvedImageRef ref =
                    imageRefResolver.resolve(ecr, req.accountId(), req.repositoryName(), req.resolveLatest(), req.tag(), req.digest(), maxPages, maxImages);

            // 2) manifest 조회
            String manifestJson = manifestFetcher.fetchManifestJson(ecr, req.accountId(), req.repositoryName(), ref);

            // 3) 저장 레이아웃
            DownloadLayout layout = buildLayout(outputDir, req, ref);

            // 4) manifest 저장
            Path manifestPath = layout.manifestPath();
            layout.writeString(manifestPath, manifestJson);

            // 5) manifest 파싱
            ParsedManifest parsed = ManifestParser.parse(manifestJson);
            if (parsed.isManifestList()) {
                throw new ApiException(ErrorCode.DOWNLOAD_FAILED,
                        "Manifest list(멀티 아키텍처)는 미지원입니다. platform 선택 옵션이 필요합니다.");
            }

            List<String> layerDigests = (parsed.layerDigests() == null) ? List.of() : parsed.layerDigests();
            String configDigest = StringUtils.trimToNull(parsed.configDigest());

            if (layerDigests.isEmpty()) {
                throw new ApiException(ErrorCode.DOWNLOAD_FAILED, "Manifest에 layers가 없습니다(비정상 manifest).");
            }

            // 6) 다운로드 실행
            DownloadExecutor.DownloadOutcome out = downloadExecutor.downloadAllBlobs(
                    ecr,
                    req.accountId(),
                    req.repositoryName(),
                    layout,
                    layerDigests,
                    configDigest,
                    includeConfig,
                    concurrency,
                    httpTimeoutSeconds,
                    maxRetries,
                    verifySha256
            );

            // 7) resolved digest 결정
            String resolvedDigest = imageRefResolver.resolveDigestIfNeeded(ecr, req.accountId(), req.repositoryName(), ref);

            return new DownloadResult(
                    ref.resolvedTag(),
                    resolvedDigest,
                    layerDigests.size(),
                    out.downloadedLayerCount(),
                    layout.basePath().toAbsolutePath().toString(),
                    manifestPath.toAbsolutePath().toString(),
                    out.configPath() == null ? null : out.configPath().toAbsolutePath().toString(),
                    "OK"
            );

        } catch (ApiException ae) {
            throw ae;
        } catch (EcrException e) {
            throw new ApiException(ErrorCode.DOWNLOAD_FAILED, "ECR 처리 실패: " + safeAwsMsg(e), null, e);
        }
    }

    // 레이아웃 생성
    private DownloadLayout buildLayout(String outputDir, EcrDownloadRequest req, ImageRefResolver.ResolvedImageRef ref) {
        String folderKey = ref.isDigest()
                ? "digest-" + stripSha256Prefix(ref.requestedDigest())
                : "tag-" + ref.resolvedTag();

        return new DownloadLayout(
                Path.of(outputDir),
                req.accountId(),
                req.region(),
                req.repositoryName(),
                folderKey
        );
    }

    private String safeAwsMsg(EcrException e) {
        String m = e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : null;
        if (m == null || m.isBlank()) m = e.getMessage();
        return m;
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private int nvl(Integer v, int def) {
        return (v == null) ? def : v;
    }

    private String stripSha256Prefix(String d) {
        if (d == null) return null;
        return d.startsWith("sha256:") ? d.substring("sha256:".length()) : d;
    }
}
