package dev.rex.demo.app.download;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.domain.image.ImageRefResolver;
import dev.rex.demo.domain.manifest.ManifestParser;
import dev.rex.demo.domain.manifest.ParsedManifest;
import dev.rex.demo.ecr.api.dto.EcrDownloadRequest;
import dev.rex.demo.infra.aws.EcrClientFactory;
import dev.rex.demo.infra.aws.ManifestFetcher;
import dev.rex.demo.infra.fs.DownloadLayout;
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

/**
 * ECR 이미지 매니페스트 관리 및 다운로드 오케스트레이션 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcrDownloadApplicationService {

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
     * ECR API를 통한 이미지 다운로드 실행
     */
    public DownloadResult downloadByEcrApi(EcrDownloadRequest req) {
        // null 방지
        Objects.requireNonNull(req, "req");

        // 옵션 정규화
        NormalizedOptions opt = normalizeOptions(req);

        // 클라이언트 생성
        try (EcrClient ecr = factory.create(
                req.region(),
                req.accessKeyId(),
                req.secretAccessKey(),
                req.sessionToken()
        )) {

            // 레퍼런스 결정
            ImageRefResolver.ResolvedImageRef ref = resolveImageRef(ecr, req, opt);

            // 매니페스트 조회
            String manifestJson = fetchManifest(ecr, req, ref);

            // 레이아웃 구성
            DownloadLayout layout = buildLayout(opt.outputDir(), req, ref);

            // 매니페스트 저장
            Path manifestPath = writeManifest(layout, manifestJson);

            // 매니페스트 검증
            ParsedManifest parsed = validateAndParseManifest(manifestJson);

            // 레이어/설정 추출
            List<String> layerDigests = safeList(parsed.layerDigests());
            String configDigest = StringUtils.trimToNull(parsed.configDigest());

            // layers 필수
            if (layerDigests.isEmpty()) {
                throw new ApiException(
                        ErrorCode.DOWNLOAD_TASK_FAILED,
                        "Manifest에 layers가 없습니다(비정상 manifest)."
                );
            }

            // 블롭 다운로드
            DownloadExecutor.DownloadOutcome out = downloadExecutor.downloadAllBlobs(
                    ecr,
                    req.accountId(),
                    req.repositoryName(),
                    layout,
                    layerDigests,
                    configDigest,
                    opt.includeConfig(),
                    opt.concurrency(),
                    opt.httpTimeoutSeconds(),
                    opt.maxRetries(),
                    opt.verifySha256()
            );

            // digest 확정
            String resolvedDigest = imageRefResolver.resolveDigestIfNeeded(
                    ecr,
                    req.accountId(),
                    req.repositoryName(),
                    ref
            );

            // 결과 반환
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
            // 비즈 예외 통과
            throw ae;

        } catch (EcrException e) {
            // ECR 실패
            throw new ApiException(
                    ErrorCode.DOWNLOAD_ECR_API_FAILED,
                    "ECR 처리 실패: " + safeAwsMsg(e),
                    null,
                    e
            );

        } catch (Exception e) {
            // 일반 실패
            throw new ApiException(
                    ErrorCode.INTERNAL_ERROR,
                    "다운로드 처리 중 예기치 못한 오류: " + safeMsg(e),
                    null,
                    e
            );
        }
    }

    private NormalizedOptions normalizeOptions(EcrDownloadRequest req) {
        // 동시성 보정
        int concurrency = clamp(nvl(req.concurrency(), defaultConcurrency), 1, maxConcurrency);

        // 타임아웃 보정
        int httpTimeoutSeconds = positiveOrDefault(req.httpTimeoutSeconds(), defaultHttpTimeoutSeconds);

        // 재시도 보정
        int maxRetries = nonNegativeOrDefault(req.maxRetries(), defaultMaxRetries);

        // 최신 탐색 제한
        int maxPages = positiveOrDefault(req.maxPages(), defaultMaxPages);
        int maxImages = positiveOrDefault(req.maxImages(), defaultMaxImages);

        // 플래그/경로
        boolean verifySha256 = req.verifySha256();
        boolean includeConfig = req.includeConfig();
        String outputDir = StringUtils.defaultIfBlank(req.outputDir(), defaultBaseDir);

        // 옵션 반환
        return new NormalizedOptions(
                concurrency,
                httpTimeoutSeconds,
                maxRetries,
                maxPages,
                maxImages,
                verifySha256,
                includeConfig,
                outputDir
        );
    }

    private ImageRefResolver.ResolvedImageRef resolveImageRef(EcrClient ecr, EcrDownloadRequest req, NormalizedOptions opt) {
        // 최신 포함 해석
        return imageRefResolver.resolve(
                ecr,
                req.accountId(),
                req.repositoryName(),
                req.resolveLatest(),
                req.tag(),
                req.digest(),
                opt.maxPages(),
                opt.maxImages()
        );
    }

    private String fetchManifest(EcrClient ecr, EcrDownloadRequest req, ImageRefResolver.ResolvedImageRef ref) {
        // JSON 획득
        return manifestFetcher.fetchManifestJson(
                ecr,
                req.accountId(),
                req.repositoryName(),
                ref
        );
    }

    private Path writeManifest(DownloadLayout layout, String manifestJson) {
        // 저장 경로
        Path manifestPath = layout.manifestPath();

        // 파일 기록
        layout.writeString(manifestPath, manifestJson);

        // 경로 반환
        return manifestPath;
    }

    private ParsedManifest validateAndParseManifest(String manifestJson) {
        // 파싱
        ParsedManifest parsed = ManifestParser.parse(manifestJson);

        // manifest list 차단
        if (parsed.isManifestList()) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Manifest list(멀티 아키텍처)는 미지원입니다. platform 선택 옵션이 필요합니다."
            );
        }

        // 정상 반환
        return parsed;
    }

    private List<String> safeList(List<String> v) {
        // null 방지
        return v == null ? List.of() : v;
    }

    /**
     * 저장 폴더 구조 정의
     */
    private DownloadLayout buildLayout(String outputDir, EcrDownloadRequest req, ImageRefResolver.ResolvedImageRef ref) {
        // 폴더 키
        String folderKey = ref.isDigest()
                ? "digest-" + stripSha256Prefix(ref.requestedDigest())
                : "tag-" + ref.resolvedTag();

        // 레이아웃 생성
        return new DownloadLayout(
                Path.of(outputDir),
                req.accountId(),
                req.region(),
                req.repositoryName(),
                folderKey
        );
    }

    /**
     * AWS 에러 메시지 추출
     */
    private String safeAwsMsg(EcrException e) {
        // AWS 메시지 우선
        String m = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorMessage() : null;
        if (StringUtils.isBlank(m)) m = e.getMessage();
        return StringUtils.defaultString(m);
    }

    private String safeMsg(Throwable t) {
        // 메시지 보호
        if (t == null) return "";
        return (t.getMessage() == null) ? t.toString() : t.getMessage();
    }

    /**
     * 수치 범위 제한
     */
    private int clamp(int v, int min, int max) {
        // clamp
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Null 처리 및 기본값 반환
     */
    private int nvl(Integer v, int def) {
        // null 대체
        return (v == null) ? def : v;
    }

    private int positiveOrDefault(Integer v, int def) {
        // 양수 보정
        if (v == null || v <= 0) return def;
        return v;
    }

    private int nonNegativeOrDefault(Integer v, int def) {
        // 0 이상 보정
        if (v == null || v < 0) return def;
        return v;
    }

    /**
     * sha256: 접두어 제거
     */
    private String stripSha256Prefix(String d) {
        // 접두어 제거
        if (d == null) return null;
        return d.startsWith("sha256:") ? d.substring("sha256:".length()) : d;
    }

    private record NormalizedOptions(
            int concurrency,
            int httpTimeoutSeconds,
            int maxRetries,
            int maxPages,
            int maxImages,
            boolean verifySha256,
            boolean includeConfig,
            String outputDir
    ) {
    }
}
