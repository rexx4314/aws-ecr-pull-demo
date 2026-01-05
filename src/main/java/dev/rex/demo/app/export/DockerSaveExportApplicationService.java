package dev.rex.demo.app.export;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.domain.manifest.ManifestParser;
import dev.rex.demo.domain.manifest.ParsedManifest;
import dev.rex.demo.ecr.api.dto.EcrDockerSaveExportRequest;
import dev.rex.demo.infra.fs.DownloadLayout;
import dev.rex.demo.infra.tar.DockerSaveNames;
import dev.rex.demo.infra.tar.DockerSaveTarWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 로컬에 저장된 ECR 다운로드 산출물을 기반으로 docker-save 형식의 tar 파일을 생성하는 서비스
 * <p>
 * - 로컬 산출물(manifest/config/레이어) 검증
 * - docker-save tar 파일 생성 호출
 * - 파일 시스템 작업 예외를 ApiException으로 래핑
 *
 * <p>
 * Clean Code 리팩터링 포인트:
 * - 메서드 분리(검증/로딩/파싱/경로결정/입력구성)
 * - early return/guard clause 유지
 * - 예외(ErrorCode) 매핑 정책 유지
 * - “로컬 산출물 기반” 정책( resolveLatest 미지원 )을 명확히 고정
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DockerSaveExportApplicationService {

    private static final String EXPORT_SUBDIR = "export";
    private static final String DOCKER_SAVE_SUBDIR = "docker-save";
    private static final String DEFAULT_FILENAME_PREFIX = "docker-save_";
    private static final String DEFAULT_FILENAME_EXT = ".tar";

    private final DockerSaveTarWriter tarWriter;

    @Value("${app.download.baseDir:./out}")
    private String defaultBaseDir;

    /**
     * 로컬 저장소 기반으로 docker-save tar를 생성하여 반환
     * <p>
     * 처리 요약:
     * - resolveLatest=true는 지원하지 않음 (로컬 산출물 기반 정책)
     * - 로컬 manifest/config 파일 유무 검사
     * - manifest 파싱 및 유효성 검사 (manifest list는 미지원)
     * - repoTag 결정 및 출력 파일명 생성
     * - tarWriter를 통해 tar 파일 생성
     *
     * @param req EcrDockerSaveExportRequest 요청 파라미터
     * @return EcrDockerSaveExportResult 생성된 tar 파일 경로 및 이미지 디렉토리
     * @throws ApiException 요청/파일시스템 오류 발생 시
     */
    public EcrDockerSaveExportResult exportDockerSaveTarFromLocal(EcrDockerSaveExportRequest req) {
        // 0) null 방지: 호출자 실수로 인한 NPE를 명확한 예외로 전환
        Objects.requireNonNull(req, "req");

        // 1) 정책 검증: export는 로컬 산출물 기반이므로 resolveLatest=true는 금지
        validatePolicy(req);

        // 2) 출력 디렉토리 확정: req.outputDir 우선, 비어있으면 defaultBaseDir 사용
        final Path outputBaseDir = resolveOutputBaseDir(req);

        // 3) 입력 레퍼런스(tag/digest) 해석: 둘 다 없으면 INVALID_REQUEST
        final ResolvedRef ref = resolveRef(req);

        // 4) 다운로드 산출물 레이아웃 생성:
        //    - download 단계에서 만든 경로 구조와 동일해야 로컬 산출물을 정확히 찾을 수 있음
        final DownloadLayout layout = buildLayout(req, outputBaseDir, ref);

        // 5) 레이아웃에서 로컬 산출물 경로 묶음으로 변환(파라미터/가독성 개선)
        final LocalArtifacts artifacts = resolveArtifacts(layout);

        // 6) 필수 파일 존재 여부 검증:
        //    - manifest.json은 항상 필요
        //    - includeConfig=true면 config.json도 필요
        validateRequiredFiles(req, artifacts);

        // 7) manifest.json 로드(파일 읽기 실패는 ApiException으로 래핑)
        final String manifestJson = readManifestJson(artifacts.ecrManifestPath());

        // 8) manifest 파싱 + 정책 검증:
        //    - manifest list(멀티 아키텍처)는 현재 export 정책상 미지원
        final ParsedManifest parsed = parseAndValidateManifest(manifestJson);

        // 9) 레이어 목록 추출/검증: layers가 비어있으면 docker-save로 만들 수 없음
        final List<String> layerDigests = validateAndGetLayerDigests(parsed);

        // 10) docker-save에서 사용할 repoTag 결정:
        //     - req.repoTag 우선
        //     - 없으면 (repoName + ":" + tag) 자동 구성 시도
        //     - 둘 다 불가하면 INVALID_REQUEST
        final String repoTag = resolveRepoTag(req);

        // 11) export 출력 디렉토리 생성:
        //     - {imageDir}/export/docker-save
        final Path exportDir = resolveExportDir(artifacts.imageDir());
        mkdirs(exportDir);

        // 12) tar 파일명 결정:
        //     - fileNameHint 우선
        //     - 없으면 docker-save_{repoTag}.tar 기본값
        //     - 파일시스템 안전한 이름으로 정규화(safeTarFileName)
        final String tarFileName = resolveTarFileName(req, repoTag);

        // 13) 최종 tar 경로 확정
        final Path tarPath = exportDir.resolve(tarFileName);

        // 14) tarWriter 입력 구성:
        //     - imageDir/manifest/config/repotag/layers/includeConfig/verifySha256 를 모아 전달
        final DockerSaveTarWriter.Input input = buildWriterInput(req, artifacts, repoTag, layerDigests);

        // 15) tar 생성 실행(실제 파일 작성/검증은 tarWriter 내부에서 수행)
        tarWriter.writeDockerSaveTar(input, tarPath);

        // 16) 결과 로깅/반환
        log.info("docker-save tar created. tarPath={}, imageDir={}",
                tarPath.toAbsolutePath(),
                artifacts.imageDir().toAbsolutePath());

        return new EcrDockerSaveExportResult(tarPath, artifacts.imageDir());
    }

    /**
     * 정책 검증:
     * - export는 로컬 산출물 기반. 최신 태그 해석(resolveLatest)은 허용하지 않음.
     */
    private void validatePolicy(EcrDockerSaveExportRequest req) {
        // 정책상 금지된 옵션을 조기에 차단하여 “왜 실패했는지”를 명확히 함
        if (req.resolveLatest()) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "export는 로컬 산출물 기반입니다. resolveLatest=true는 지원하지 않습니다. 먼저 download로 tag를 확정하세요."
            );
        }
    }

    /**
     * outputDir 결정:
     * - 요청값 우선
     * - 비어있으면 defaultBaseDir 사용
     */
    private Path resolveOutputBaseDir(EcrDockerSaveExportRequest req) {
        // outputDir 문자열이 비어있거나 null이면 defaultBaseDir로 대체
        String outputDir = StringUtils.defaultIfBlank(req.outputDir(), defaultBaseDir);

        // Path로 변환하여 이후 파일/디렉토리 연산을 일관되게 처리
        return Path.of(outputDir);
    }

    /**
     * DownloadLayout 구성:
     * - download 단계에서 사용한 동일한 규칙(경로 구조)을 export에서도 재사용해야 함
     */
    private DownloadLayout buildLayout(EcrDockerSaveExportRequest req, Path outputBaseDir, ResolvedRef ref) {
        // folderKey는 “tag 기반” 또는 “digest 기반”으로 이미지 단위 폴더를 구분하는 핵심 키
        return new DownloadLayout(
                outputBaseDir,
                req.accountId(),
                req.region(),
                req.repositoryName(),
                ref.folderKey()
        );
    }

    /**
     * layout으로부터 실제 파일 경로들을 모아 “로컬 산출물”로 취급
     */
    private LocalArtifacts resolveArtifacts(DownloadLayout layout) {
        // DownloadLayout이 관리하는 기준 경로들을 묶어 전달(메서드 파라미터 감소/가독성 향상)
        return new LocalArtifacts(
                layout.basePath(),
                layout.manifestPath(),
                layout.configPath()
        );
    }

    /**
     * 로컬 산출물 존재 여부 검증
     * - manifest.json은 항상 필수
     * - includeConfig=true이면 config.json도 필수
     */
    private void validateRequiredFiles(EcrDockerSaveExportRequest req, LocalArtifacts artifacts) {
        // export는 "로컬 산출물"을 전제로 하므로, manifest.json이 없으면 선행 작업(download)이 빠진 것
        requireFile(artifacts.ecrManifestPath(), "manifest.json이 없습니다. 먼저 /api/ecr/download를 호출하세요.");

        // config.json은 includeConfig로 내려받은 경우에만 필수
        if (req.includeConfig()) {
            requireFile(artifacts.configPath(), "config.json이 없습니다. includeConfig=true로 다운로드했는지 확인하세요.");
        }
    }

    /**
     * manifest.json 로딩
     * - IOException은 ApiException으로 래핑(기존 ErrorCode 유지)
     */
    private String readManifestJson(Path manifestPath) {
        try {
            // 로컬 파일에서 manifest.json을 문자열로 읽음(UTF-8 기본)
            return Files.readString(manifestPath);
        } catch (IOException ioe) {
            // 파일 읽기 실패는 FS 계열 ErrorCode로 래핑(기존 정책 유지)
            throw new ApiException(
                    ErrorCode.DOWNLOAD_FS_WRITE_FAILED,
                    "manifest.json 읽기 실패: " + ioe.getMessage(),
                    null,
                    ioe
            );
        }
    }

    /**
     * manifest 파싱 + 기능 제약 검증
     * - manifest list(멀티 아키텍처)는 현재 정책상 미지원
     */
    private ParsedManifest parseAndValidateManifest(String manifestJson) {
        // JSON 파싱 및 필요한 필드(digest/config/layers 등) 추출
        ParsedManifest parsed = ManifestParser.parse(manifestJson);

        // 멀티 아키텍처(manifest list)는 platform 선택 기능이 필요하므로 현재는 요청 자체를 차단
        if (parsed.isManifestList()) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "멀티 아키텍처(manifest list)는 export 미지원입니다. platform 선택 기능이 필요합니다."
            );
        }
        return parsed;
    }

    /**
     * 레이어 목록 검증
     * - layers가 없으면 정상적인 이미지 산출물로 보기 어려움
     */
    private List<String> validateAndGetLayerDigests(ParsedManifest parsed) {
        // manifest에서 레이어 digest 목록을 추출
        List<String> layerDigests = parsed.layerDigests();

        // docker-save는 레이어 tar들이 필수이므로 비어있으면 실패 처리
        if (layerDigests == null || layerDigests.isEmpty()) {
            throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "manifest에 layers가 없습니다.");
        }
        return layerDigests;
    }

    /**
     * docker-save에서 사용할 repoTag 결정
     * 우선순위:
     * - req.repoTag()가 있으면 그대로 사용
     * - 없으면 tag로 "repo:tag" 자동 구성
     */
    private String resolveRepoTag(EcrDockerSaveExportRequest req) {
        // 1) 요청에서 repoTag가 직접 오면 최우선
        String repoTag = StringUtils.trimToNull(req.repoTag());

        // 2) repoTag가 없다면, tag가 있는 경우 "repositoryName:tag"로 자동 구성
        if (repoTag == null) {
            String tag = StringUtils.trimToNull(req.tag());
            if (tag != null) {
                repoTag = req.repositoryName() + ":" + tag;
            }
        }

        // 3) 여전히 없으면 요청 자체가 불완전: export가 사용할 repoTag를 확정할 수 없음
        if (repoTag == null) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "repoTag가 비었습니다. tag 기반 export는 repoTag를 자동 구성할 수 있어야 합니다."
            );
        }

        return repoTag;
    }

    /**
     * export 출력 디렉토리:
     * - {imageDir}/export/docker-save
     */
    private Path resolveExportDir(Path imageDir) {
        // imageDir 하위에 export/docker-save 폴더를 생성하여 export 산출물을 분리 저장
        return imageDir.resolve(EXPORT_SUBDIR).resolve(DOCKER_SAVE_SUBDIR);
    }

    /**
     * 출력 파일명 결정:
     * - fileNameHint가 있으면 우선
     * - 없으면 docker-save_{repoTag}.tar 형태 기본값
     * - 파일 시스템 안전한 파일명으로 정규화
     */
    private String resolveTarFileName(EcrDockerSaveExportRequest req, String repoTag) {
        // 기본 파일명: repoTag가 들어가므로 사람이 식별하기 쉬움
        String defaultName = DEFAULT_FILENAME_PREFIX + repoTag + DEFAULT_FILENAME_EXT;

        // 사용자가 fileNameHint를 줬다면 우선 사용
        String hint = StringUtils.defaultIfBlank(req.fileNameHint(), defaultName);

        // 파일명에 포함될 수 있는 위험 문자/경로 문자를 정리하여 안전한 이름으로 변환
        return DockerSaveNames.safeTarFileName(hint);
    }

    /**
     * Writer 입력 구성
     * - verifySha256 옵션은 writer에서 레이어 sha256 검증 시 사용
     */
    private DockerSaveTarWriter.Input buildWriterInput(
            EcrDockerSaveExportRequest req,
            LocalArtifacts artifacts,
            String repoTag,
            List<String> layerDigests
    ) {
        // tarWriter는 Input을 기반으로 docker-save 표준 구조(manifest.json, repositories, layer.tar 등)를 구성
        return new DockerSaveTarWriter.Input(
                artifacts.imageDir(),
                artifacts.ecrManifestPath(),
                artifacts.configPath(),
                repoTag,
                layerDigests,
                req.includeConfig(),
                req.verifySha256()
        );
    }

    /**
     * 요청에서 tag 또는 digest를 해석하여 내부 참조로 변환
     *
     * @param req 요청 객체
     * @return ResolvedRef tag 또는 digest 기반 참조
     * @throws ApiException tag/digest 둘 다 없으면 발생
     */
    private ResolvedRef resolveRef(EcrDockerSaveExportRequest req) {
        // 입력값을 trim하여 공백/빈 문자열을 제거
        String tag = StringUtils.trimToNull(req.tag());
        String digest = StringUtils.trimToNull(req.digest());

        // digest가 우선(정확한 이미지 식별)
        if (digest != null) {
            return ResolvedRef.byDigest(digest);
        }

        // tag는 동일 태그가 이동할 수 있지만, 로컬 산출물 기반에서는 "다운로드 시점의 tag 폴더"를 사용
        if (tag != null) {
            return ResolvedRef.byTag(tag);
        }

        // 둘 다 없으면 어떤 로컬 산출물을 찾을지 결정할 수 없음
        throw new ApiException(ErrorCode.INVALID_REQUEST, "tag 또는 digest 중 하나가 필요합니다.");
    }

    /**
     * 특정 경로에 파일이 존재하는지 확인하고, 없으면 ApiException 발생
     *
     * @param p   검사할 경로
     * @param msg 오류 시 사용자 메시지
     * @throws ApiException 파일이 없거나 정규 파일이 아니면 발생
     */
    private void requireFile(Path p, String msg) {
        // Files.isRegularFile: 존재 + regular file 여부를 함께 확인(디렉토리/심볼릭링크 등 제외)
        if (p == null || !Files.isRegularFile(p)) {
            // “로컬 산출물이 없음”은 보통 작업 순서 문제/산출물 미생성
            throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, msg);
        }
    }

    /**
     * 디렉토리 생성. 실패 시 ApiException으로 래핑
     *
     * @param dir 생성할 디렉토리 경로
     * @throws ApiException 디렉토리 생성 실패 시 발생
     */
    private void mkdirs(Path dir) {
        try {
            // 중간 경로까지 모두 생성(createDirectories는 이미 존재하면 정상 처리)
            Files.createDirectories(dir);
        } catch (IOException e) {
            // 파일 시스템 쓰기 실패는 FS 계열 ErrorCode로 래핑(기존 정책 유지)
            throw new ApiException(
                    ErrorCode.DOWNLOAD_FS_WRITE_FAILED,
                    "디렉토리 생성 실패: " + dir + ", err=" + e.getMessage(),
                    null,
                    e
            );
        }
    }

    /**
     * 내부 이미지 참조 DTO
     * <p>
     * digest=true인 경우 digestValue 사용, 그렇지 않으면 tag 사용
     */
    private record ResolvedRef(boolean digest, String tag, String digestValue) {
        /**
         * 태그 기반 참조 생성
         *
         * @param tag 태그 이름
         * @return ResolvedRef
         */
        static ResolvedRef byTag(String tag) {
            return new ResolvedRef(false, tag, null);
        }

        /**
         * 다이제스트 기반 참조 생성
         *
         * @param digest 다이제스트 문자열 (예: sha256:...)
         * @return ResolvedRef
         */
        static ResolvedRef byDigest(String digest) {
            return new ResolvedRef(true, null, digest);
        }

        /**
         * 폴더 키 생성 규칙:
         * - digest 기반: "digest-{sha256 해시}"
         * - tag 기반: "tag-{tag}"
         *
         * @return 폴더 키 문자열
         */
        String folderKey() {
            // digest 기반이면 sha256: 접두어를 제거해 폴더명이 지나치게 길거나 중복되지 않게 정규화
            if (digest) {
                return "digest-" + DockerSaveNames.stripSha256Prefix(digestValue);
            }
            // tag 기반이면 tag 값을 그대로 사용(다운로드 시 생성된 tag-{tag} 폴더와 일치해야 함)
            return "tag-" + tag;
        }
    }

    /**
     * 로컬 산출물 경로 묶음(가독성/파라미터 감소 목적)
     * - imageDir: 이미지 단위 최상위 디렉토리
     * - ecrManifestPath: ECR manifest.json
     * - configPath: config.json (includeConfig=true일 때 필수)
     */
    private record LocalArtifacts(Path imageDir, Path ecrManifestPath, Path configPath) {
    }
}
