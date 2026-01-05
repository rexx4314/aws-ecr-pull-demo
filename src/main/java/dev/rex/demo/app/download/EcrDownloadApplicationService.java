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
 * <p>
 * - 이미지 참조 해석 (태그/다이제스트/최신)
 * - 매니페스트 조회 및 검증
 * - 다운로드 레이아웃 구성 및 블롭 다운로드 호출
 *
 * <p>
 * 팀 공유용 Clean Code 리팩터링 포인트:
 * - “오케스트레이션” 메서드는 흐름만 남기고 세부 구현은 private 메서드로 분리
 * - 매직 스트링/접두어를 상수화하고, 공통 변환 로직을 한 곳으로 모음
 * - 예외 처리 정책(ErrorCode/메시지)은 기존 의도를 유지(필요한 곳에만 래핑)
 * - layout 폴더키 규칙을 단일 책임으로 캡슐화(추후 export와 규칙 공유/일관성 확보 용이)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcrDownloadApplicationService {

    private static final String DIGEST_PREFIX = "sha256:";
    private static final String FOLDER_KEY_DIGEST_PREFIX = "digest-";
    private static final String FOLDER_KEY_TAG_PREFIX = "tag-";

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
     * <p>
     * 흐름:
     * 1. 요청 옵션 정규화
     * 2. ECR 클라이언트 생성 및 이미지 레퍼런스 해석
     * 3. 매니페스트 조회/검증 및 저장
     * 4. 레이어/설정 병렬 다운로드 실행
     *
     * @param req 다운로드 요청 DTO
     * @return DownloadResult 요약 결과
     * @throws ApiException 비즈니스/처리 예외
     */
    public DownloadResult downloadByEcrApi(EcrDownloadRequest req) {
        // 0) null 방지: 호출자 실수(NPE)를 명확한 예외로 전환
        Objects.requireNonNull(req, "req");

        // 1) 옵션 정규화(기본값 적용 + 허용 범위 보정)
        //    - concurrency, timeout, retries, scan limits 등을 일관된 정책으로 확정
        NormalizedOptions opt = normalizeOptions(req);

        // 2) ECR 클라이언트 생성(try-with-resources로 안전하게 close)
        //    - 여기서부터는 “오케스트레이션 흐름”만 남기고 세부는 private 메서드로 위임
        try (EcrClient ecr = createEcrClient(req)) {

            // 3) 이미지 레퍼런스 해석(tag/digest/latest)
            //    - resolveLatest=true인 경우 최신 태그를 스캔해 resolvedTag/digest 후보를 결정
            ImageRefResolver.ResolvedImageRef ref = resolveImageRef(ecr, req, opt);

            // 4) 매니페스트 조회(ECR API)
            //    - 이미지 레퍼런스(ref)에 해당하는 manifest JSON 문자열을 획득
            String manifestJson = fetchManifest(ecr, req, ref);

            // 5) 매니페스트 파싱/검증
            //    - manifest list(멀티 아키텍처) 등 현재 미지원 형식을 이 단계에서 차단
            ParsedManifest parsed = validateAndParseManifest(manifestJson);

            // 6) 저장 레이아웃 확정(로컬 저장 경로)
            //    - folderKey 규칙은 buildFolderKey에 캡슐화(추후 export와 동일 규칙 공유 용이)
            DownloadLayout layout = buildLayout(opt.outputDir(), req, ref);

            // 7) 매니페스트 저장
            //    - 로컬 산출물(manifest.json)을 먼저 저장하여, 이후 단계에서 재사용 가능하게 함
            Path manifestPath = writeManifest(layout, manifestJson);

            // 8) 다운로드 계획 구성
            //    - layers/configDigest를 추출하고 필수 조건(layers 존재)을 검증
            DownloadPlan plan = buildDownloadPlan(parsed);

            // 9) 레이어/설정 blob 다운로드(병렬)
            //    - DownloadExecutor가 토큰 획득/병렬 제출/실패 집계/부분 성공 정책을 담당
            DownloadExecutor.DownloadOutcome out = downloadExecutor.downloadAllBlobs(
                    ecr,
                    req.accountId(),
                    req.repositoryName(),
                    layout,
                    plan.layerDigests(),
                    plan.configDigest(),
                    opt.includeConfig(),
                    opt.concurrency(),
                    opt.httpTimeoutSeconds(),
                    opt.maxRetries(),
                    opt.verifySha256()
            );

            // 10) digest 확정(필요 시)
            //     - tag/latest 기반 요청은 실제 digest를 확정하여 결과에 포함
            String resolvedDigest = resolveDigestIfNeeded(ecr, req, ref);

            // 11) 결과 DTO 구성(반환 포맷을 한 곳에서 관리)
            return buildResult(ref, resolvedDigest, plan.layerDigests(), layout, manifestPath, out);

        } catch (ApiException ae) {
            // 12) 이미 의도된 ApiException은 그대로 전파(코드/메시지 정책 보존)
            throw ae;

        } catch (EcrException e) {
            // 13) AWS ECR 예외는 표준 ErrorCode로 래핑
            //     - 사용자 메시지는 safeAwsMsg로 안전하게 추출
            throw new ApiException(
                    ErrorCode.DOWNLOAD_ECR_API_FAILED,
                    "ECR 처리 실패: " + safeAwsMsg(e),
                    null,
                    e
            );

        } catch (Exception e) {
            // 14) 그 외 예외는 INTERNAL_ERROR로 래핑(예상치 못한 런타임 실패)
            throw new ApiException(
                    ErrorCode.INTERNAL_ERROR,
                    "다운로드 처리 중 예기치 못한 오류: " + safeMsg(e),
                    null,
                    e
            );
        }
    }

    /**
     * ECR 클라이언트 생성
     */
    private EcrClient createEcrClient(EcrDownloadRequest req) {
        // AWS SDK 클라이언트는 close 대상이므로 호출자는 try-with-resources로 감싸야 함
        return factory.create(
                req.region(),
                req.accessKeyId(),
                req.secretAccessKey(),
                req.sessionToken()
        );
    }

    /**
     * 요청 옵션을 검증하고 기본값을 적용한 정규화된 옵션 생성
     *
     * @param req 원본 요청
     * @return NormalizedOptions 검증된 옵션
     */
    private NormalizedOptions normalizeOptions(EcrDownloadRequest req) {
        // 1) concurrency 보정
        //    - null이면 defaultConcurrency
        //    - 최소 1, 최대 maxConcurrency로 clamp
        int concurrency = clamp(nvl(req.concurrency(), defaultConcurrency), 1, maxConcurrency);

        // 2) httpTimeoutSeconds 보정
        //    - null 또는 <=0이면 defaultHttpTimeoutSeconds 적용
        int httpTimeoutSeconds = positiveOrDefault(req.httpTimeoutSeconds(), defaultHttpTimeoutSeconds);

        // 3) maxRetries 보정
        //    - null 또는 <0이면 defaultMaxRetries 적용(0 이상 허용)
        int maxRetries = nonNegativeOrDefault(req.maxRetries(), defaultMaxRetries);

        // 4) resolveLatest 스캔 제한 보정
        //    - maxPages/maxImages는 과도한 스캔을 방지하기 위한 안전장치
        int maxPages = positiveOrDefault(req.maxPages(), defaultMaxPages);
        int maxImages = positiveOrDefault(req.maxImages(), defaultMaxImages);

        // 5) 플래그/경로 확정
        //    - verifySha256/includeConfig는 그대로 사용
        //    - outputDir이 비어있으면 defaultBaseDir 적용
        boolean verifySha256 = req.verifySha256();
        boolean includeConfig = req.includeConfig();
        String outputDir = StringUtils.defaultIfBlank(req.outputDir(), defaultBaseDir);

        // 6) 정규화 옵션 반환(이후 단계는 opt만 참조해도 동작하도록 일관성 확보)
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

    /**
     * 이미지 참조를 해석하여 실제 태그/다이제스트 정보를 반환
     *
     * @param ecr ECR 클라이언트
     * @param req 요청 정보
     * @param opt 정규화된 옵션
     * @return ResolvedImageRef 해석 결과
     */
    private ImageRefResolver.ResolvedImageRef resolveImageRef(EcrClient ecr, EcrDownloadRequest req, NormalizedOptions opt) {
        // resolveLatest=true이면:
        // - repo 내 이미지들을 페이지/개수 제한(opt.maxPages/maxImages) 안에서 탐색하여 최신을 결정
        // resolveLatest=false이면:
        // - tag 또는 digest 입력을 기반으로 ref를 구성
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

    /**
     * 매니페스트 JSON을 획득하여 반환
     *
     * @param ecr ECR 클라이언트
     * @param req 요청 정보
     * @param ref 해석된 이미지 참조
     * @return 매니페스트 JSON 문자열
     */
    private String fetchManifest(EcrClient ecr, EcrDownloadRequest req, ImageRefResolver.ResolvedImageRef ref) {
        // ref(tag/digest/latest 해석 결과)에 해당하는 매니페스트 JSON을 ECR에서 조회
        return manifestFetcher.fetchManifestJson(
                ecr,
                req.accountId(),
                req.repositoryName(),
                ref
        );
    }

    /**
     * 매니페스트를 파싱하고 지원 여부를 검증
     *
     * @param manifestJson 매니페스트 JSON
     * @return ParsedManifest 파싱 결과
     * @throws ApiException manifest list 등 미지원 형식인 경우
     */
    private ParsedManifest validateAndParseManifest(String manifestJson) {
        // JSON 파싱(필드 추출: layers, configDigest 등)
        ParsedManifest parsed = ManifestParser.parse(manifestJson);

        // manifest list(멀티 아키텍처)는 platform 선택이 필요하므로 현 정책에서는 차단
        if (parsed.isManifestList()) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "Manifest list(멀티 아키텍처)는 미지원입니다. platform 선택 옵션이 필요합니다."
            );
        }

        return parsed;
    }

    /**
     * 다운로드에 필요한 레이어/설정 정보를 구성하고 필수 조건을 검증
     * - layers는 반드시 존재해야 함
     */
    private DownloadPlan buildDownloadPlan(ParsedManifest parsed) {
        // 1) layers/configDigest 추출
        //    - layers는 null 안전 처리
        //    - configDigest는 공백 제거 후 null 가능
        List<String> layerDigests = safeList(parsed.layerDigests());
        String configDigest = StringUtils.trimToNull(parsed.configDigest());

        // 2) 필수 조건 검증: 레이어가 없으면 정상 이미지로 보기 어려움
        //    - 이후 다운로드/검증/압축 과정이 모두 무의미해지므로 여기서 조기 실패
        if (layerDigests.isEmpty()) {
            throw new ApiException(
                    ErrorCode.DOWNLOAD_TASK_FAILED,
                    "Manifest에 layers가 없습니다(비정상 manifest)."
            );
        }

        // 3) 다운로드 계획 반환
        //    - configDigest는 includeConfig 옵션에 따라 실제 다운로드 대상이 될 수도/안될 수도 있음
        return new DownloadPlan(layerDigests, configDigest);
    }

    /**
     * 매니페스트 파일을 로컬에 기록하고 경로를 반환
     *
     * @param layout       레이아웃
     * @param manifestJson 매니페스트 JSON
     * @return 저장된 매니페스트 경로
     */
    private Path writeManifest(DownloadLayout layout, String manifestJson) {
        // 1) 매니페스트 저장 위치
        Path manifestPath = layout.manifestPath();

        // 2) 파일 기록(DownloadLayout이 FS 예외/정책을 캡슐화한다고 가정)
        layout.writeString(manifestPath, manifestJson);

        // 3) 저장된 경로 반환
        return manifestPath;
    }

    /**
     * digest 확정(필요 시)
     * - 요청이 tag/latest 기반이면 실제 digest로 확정
     * - digest 요청이면 그대로 반환될 가능성이 큼(Resolver 정책에 따름)
     */
    private String resolveDigestIfNeeded(EcrClient ecr, EcrDownloadRequest req, ImageRefResolver.ResolvedImageRef ref) {
        // ref가 tag/latest 기반일 수 있으므로, 최종적으로 digest를 확정해 결과에 포함
        // (다운로드 산출물/폴더키/추후 export 등에서 digest가 필요할 수 있음)
        return imageRefResolver.resolveDigestIfNeeded(
                ecr,
                req.accountId(),
                req.repositoryName(),
                ref
        );
    }

    /**
     * DownloadResult 생성(반환 형태를 한 곳에서 관리)
     */
    private DownloadResult buildResult(
            ImageRefResolver.ResolvedImageRef ref,
            String resolvedDigest,
            List<String> layerDigests,
            DownloadLayout layout,
            Path manifestPath,
            DownloadExecutor.DownloadOutcome out
    ) {
        // DTO를 한 곳에서 구성하면 필드 추가/변경 시 수정 범위를 최소화할 수 있음
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
    }

    /**
     * 저장 폴더 구조 정의 및 DownloadLayout 생성
     *
     * @param outputDir 출력 디렉토리
     * @param req       요청 정보
     * @param ref       해석된 이미지 참조
     * @return DownloadLayout 생성된 레이아웃
     */
    private DownloadLayout buildLayout(String outputDir, EcrDownloadRequest req, ImageRefResolver.ResolvedImageRef ref) {
        // folderKey는 “이미지 단위 저장 폴더”를 결정하는 핵심 값
        // - tag 기반: tag-<resolvedTag>
        // - digest 기반: digest-<sha256 hash>
        String folderKey = buildFolderKey(ref);

        // DownloadLayout은 아래 요소를 기준으로 파일 저장 구조를 표준화
        // - outputDir / accountId / region / repository / folderKey / (manifest, blobs, config...)
        return new DownloadLayout(
                Path.of(outputDir),
                req.accountId(),
                req.region(),
                req.repositoryName(),
                folderKey
        );
    }

    /**
     * folderKey 생성 규칙을 단일 메서드로 고정
     * - digest 기반: "digest-{sha256 hash}"
     * - tag 기반: "tag-{resolvedTag}"
     *
     * <p>
     * 주의:
     * - digest 요청 시 requestedDigest()가 "sha256:..." 형태일 수 있어 prefix 제거가 필요
     * - tag 기반은 resolvedTag()를 사용(최신 해석/정규화 반영)
     */
    private String buildFolderKey(ImageRefResolver.ResolvedImageRef ref) {
        // digest 기반이면:
        // - 폴더키에 "sha256:" 접두어를 그대로 두면 불필요하게 길고(또는 다른 모듈 규칙과 불일치)
        // - 따라서 접두어 제거 후 digest-<hash>로 통일
        if (ref.isDigest()) {
            String d = stripSha256Prefix(ref.requestedDigest());
            return FOLDER_KEY_DIGEST_PREFIX + d;
        }

        // tag 기반이면:
        // - resolvedTag()를 사용해 latest 해석/정규화 결과를 폴더키에 반영
        return FOLDER_KEY_TAG_PREFIX + ref.resolvedTag();
    }

    /**
     * null 안전 리스트 반환
     *
     * @param v 원본 리스트
     * @return 빈 리스트 또는 원본
     */
    private List<String> safeList(List<String> v) {
        // null이면 빈 리스트 반환하여 이후 로직에서 NPE 없이 처리
        return v == null ? List.of() : v;
    }

    /**
     * ECR 예외에서 사용자 친화적 메시지 추출
     *
     * @param e EcrException
     * @return 메시지 문자열
     */
    private String safeAwsMsg(EcrException e) {
        // AWS SDK는 상세 메시지가 awsErrorDetails에 있을 수 있으므로 우선 사용
        String m = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorMessage() : null;

        // 없으면 기본 메시지로 대체
        if (StringUtils.isBlank(m)) m = e.getMessage();

        // 최종적으로 null 방지
        return Objects.toString(m, "");
    }

    /**
     * 일반 예외의 메시지 안전 추출
     *
     * @param t 예외
     * @return 메시지 또는 toString()
     */
    private String safeMsg(Throwable t) {
        // null 방지
        if (t == null) return "";

        // 메시지가 없으면 toString으로 대체(클래스/원인 정보 포함 가능)
        return (t.getMessage() == null) ? t.toString() : t.getMessage();
    }

    /**
     * 값의 범위를 min~max로 제한(clamp)
     *
     * @param v   원값
     * @param min 최소
     * @param max 최대
     * @return 제한된 값
     */
    private int clamp(int v, int min, int max) {
        // 동시성/한도 값이 과도하게 커지거나 0 이하로 내려가는 것을 방지
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Integer null을 기본값으로 대체
     *
     * @param v   입력값
     * @param def 기본값
     * @return v 또는 def
     */
    private int nvl(Integer v, int def) {
        // null이면 기본값 적용
        return (v == null) ? def : v;
    }

    /**
     * 양수 보정: v가 null 또는 <=0이면 기본값 반환
     */
    private int positiveOrDefault(Integer v, int def) {
        // 0 이하 값은 의미가 없으므로 기본값으로 보정
        if (v == null || v <= 0) return def;
        return v;
    }

    /**
     * 0 이상 보정: v가 null 또는 <0이면 기본값 반환
     */
    private int nonNegativeOrDefault(Integer v, int def) {
        // 음수는 의미가 없으므로 기본값으로 보정(0은 허용)
        if (v == null || v < 0) return def;
        return v;
    }

    /**
     * "sha256:" 접두어를 제거하여 해시만 반환
     *
     * @param d digest 문자열
     * @return 접두어 제거된 문자열 또는 null
     */
    private String stripSha256Prefix(String d) {
        // null 방지
        if (d == null) return null;

        // "sha256:" 접두어가 있으면 제거하여 hash만 사용
        return d.startsWith(DIGEST_PREFIX) ? d.substring(DIGEST_PREFIX.length()) : d;
    }

    /**
     * 정규화된 다운로드 옵션 DTO
     * <p>
     * 모든 필드는 검증 및 기본값 적용된 상태
     */
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

    /**
     * 다운로드 실행을 위한 계획(파싱 결과에서 필요한 값만 추출)
     * - layerDigests는 반드시 비어있지 않음(검증됨)
     * - configDigest는 없을 수 있음(옵션/매니페스트 형태에 따라)
     */
    private record DownloadPlan(
            List<String> layerDigests,
            String configDigest
    ) {
    }
}
