package dev.rex.demo.infra.fs;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * 다운로드/내보내기 파일 레이아웃(디렉토리 구조) 정의 클래스
 * <p>
 * 책임:
 * - rootDir/accountId/region/repositoryName/folderKey를 기반으로 "베이스 경로"를 결정
 * - blobs/sha256, export/docker-save 등의 하위 디렉토리 생성 보장
 * - manifest/config/blob/export 경로를 일관된 규칙으로 제공
 * - 파일/디렉토리 관련 I/O 유틸(writeString, mkdirs) 제공
 *
 * <p>
 * 설계 포인트(팀 공유용):
 * - “경로 규칙”과 “I/O 수행”을 한 클래스에 두되, 메서드 역할을 명확히 분리
 * - 경로 구성 시 외부 입력(accountId/region/folderKey)은 sanitize 처리
 * - repositoryName은 ECR repo 특성상 nested 가능하므로(Path로 변환) "/" 구조 허용
 * - digest는 sha256:{hex} 또는 {hex} 모두 지원
 */
public class DownloadLayout {

    private static final String DIR_BLOBS = "blobs";
    private static final String DIR_SHA256 = "sha256";

    private static final String DIR_EXPORT = "export";
    private static final String DIR_DOCKER_SAVE = "docker-save";

    private static final String FILE_MANIFEST = "manifest.json";
    private static final String FILE_CONFIG = "config.json";
    private static final String DEFAULT_EMPTY_SEGMENT = "_";

    private static final String DIGEST_PREFIX = "sha256:";

    private final Path basePath;

    /**
     * 레이아웃 생성자
     * <p>
     * 생성 시점에 다음 디렉토리를 반드시 만들어 둠:
     * - {base}/blobs/sha256
     * - {base}/export/docker-save
     *
     * @param rootDir        최상위 출력 디렉토리(필수)
     * @param accountId      AWS 계정/레지스트리 ID(경로 세그먼트로 사용됨)
     * @param region         AWS region(경로 세그먼트로 사용됨)
     * @param repositoryName ECR repository name(중첩 가능: a/b/c)
     * @param folderKey      tag/digest 기반 폴더키(경로 세그먼트로 사용됨)
     */
    public DownloadLayout(Path rootDir, String accountId, String region, String repositoryName, String folderKey) {
        // 0) 필수 입력 방어: rootDir이 null이면 경로 생성 불가
        Objects.requireNonNull(rootDir, "rootDir");

        // 1) basePath 규칙 구성
        //    - accountId/region/folderKey는 sanitize 처리하여 path traversal/특수문자 문제를 완화
        //    - repositoryName은 nested path를 허용(예: team/app)
        this.basePath = rootDir
                .resolve(sanitizeSegment(accountId))
                .resolve(sanitizeSegment(region))
                .resolve(repoAsPath(repositoryName))
                .resolve(sanitizeSegment(folderKey));

        // 2) 필수 디렉토리 생성(초기화)
        //    - blob 저장 공간
        mkdirs(blobsSha256Dir());
        //    - docker save export 공간
        mkdirs(exportDockerSaveDir());
    }

    /**
     * 디렉토리 생성(없으면 생성)
     * <p>
     * - Files.createDirectories는 "이미 있으면 OK"라서 안전
     * - IOException은 호출자가 처리하기 어려우므로 IllegalStateException으로 래핑
     */
    public static void mkdirs(Path dir) {
        if (dir == null) return;

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("디렉토리 생성 실패: " + dir + ", err=" + e.getMessage(), e);
        }
    }

    /**
     * digest 문자열을 hex로 정규화
     * <p>
     * - "sha256:{hex}" -> "{hex}"
     * - "{hex}" -> "{hex}"
     *
     * @throws IllegalArgumentException digest가 blank인 경우
     */
    private static String toDigestHex(String digest) {
        String d = StringUtils.trimToNull(digest);
        if (d == null) throw new IllegalArgumentException("digest is blank");

        // ECR은 보통 sha256: 접두어를 포함해서 내려준다.
        if (d.startsWith(DIGEST_PREFIX)) {
            return d.substring(DIGEST_PREFIX.length());
        }
        return d;
    }

    /**
     * 경로 세그먼트용 sanitize
     * <p>
     * 의도:
     * - Path.resolve에 들어갈 "단일 세그먼트" 값(accountId, region, folderKey 등)을
     * 안전하게 만들기 위함
     * <p>
     * 처리:
     * - null/blank -> "_"
     * - ".." -> "_" (상위 이동 방지)
     * - ":" -> "_" (Windows 등에서 불편한 문자 완화)
     * <p>
     * 주의:
     * - 여기서는 최소한의 sanitize만 수행
     * - repoName은 nested를 허용해야 해서 별도 처리(repoAsPath)
     */
    private static String sanitizeSegment(String s) {
        String t = StringUtils.trimToNull(s);
        if (t == null) return DEFAULT_EMPTY_SEGMENT;

        // 아주 기초적인 path traversal/OS 불호환 문자 완화
        return t.replace("..", DEFAULT_EMPTY_SEGMENT)
                .replace(":", DEFAULT_EMPTY_SEGMENT);
    }

    /**
     * repositoryName을 Path로 변환 (nested 허용)
     * <p>
     * 예: "team/app" -> Path("team/app")
     * <p>
     * 방어:
     * - null/blank면 "_" 반환
     * - InvalidPathException이면 "_" 반환(비정상 repo명 방어)
     * <p>
     * 주의:
     * - repoName이 "../" 같은 traversal을 포함할 가능성이 걱정되면,
     * 이 지점에서 추가 정책(예: normalize 후 ".." 포함 여부 검사)을 더 강하게 걸 수 있음
     */
    private static Path repoAsPath(String repo) {
        String t = StringUtils.trimToNull(repo);
        if (t == null) return Path.of(DEFAULT_EMPTY_SEGMENT);

        try {
            // ECR repository name은 "/"를 포함할 수 있으므로 그대로 Path로 둠
            return Path.of(t);
        } catch (InvalidPathException ipe) {
            // 운영체제별로 허용되지 않는 문자가 들어간 경우 방어
            return Path.of(DEFAULT_EMPTY_SEGMENT);
        }
    }

    /**
     * 레이아웃의 기준(base) 경로
     */
    public Path basePath() {
        return basePath;
    }

    /**
     * manifest.json 저장 경로
     * - ECR BatchGetImage로 받은 원본 manifest JSON
     */
    public Path manifestPath() {
        return basePath.resolve(FILE_MANIFEST);
    }

    /**
     * config.json 저장 경로
     * - manifest config digest로 내려받은 raw config blob
     */
    public Path configPath() {
        return basePath.resolve(FILE_CONFIG);
    }

    /**
     * blobs/sha256 디렉토리 경로
     * - 모든 레이어 blob은 sha256 hex 파일명으로 저장
     */
    public Path blobsSha256Dir() {
        return basePath.resolve(DIR_BLOBS).resolve(DIR_SHA256);
    }

    /**
     * digest에 해당하는 blob 파일 경로를 반환
     * <p>
     * - 입력 digest는 "sha256:{hex}" 또는 "{hex}" 모두 허용
     * - 최종 저장 파일명은 "{hex}"
     */
    public Path blobPathForDigest(String digest) {
        String hex = toDigestHex(digest);
        return blobsSha256Dir().resolve(hex);
    }

    /**
     * docker-save export 디렉토리 경로
     */
    public Path exportDockerSaveDir() {
        return basePath.resolve(DIR_EXPORT).resolve(DIR_DOCKER_SAVE);
    }

    /**
     * docker-save tar 파일 경로 생성
     * <p>
     * 규칙:
     * - fileName이 blank면 기본 "image.tar"
     * - ".tar" 확장자가 없으면 자동으로 붙임
     * - export/docker-save 디렉토리는 생성 보장
     */
    public Path exportDockerSaveTarPath(String fileName) {
        // 1) 파일명 정규화
        String fn = StringUtils.trimToNull(fileName);
        if (fn == null) fn = "image.tar";
        if (!fn.endsWith(".tar")) fn = fn + ".tar";

        // 2) 디렉토리 생성 보장 후 반환
        Path dir = exportDockerSaveDir();
        mkdirs(dir);
        return dir.resolve(fn);
    }

    /**
     * UTF-8 문자열을 파일로 저장
     * <p>
     * - parent 디렉토리 생성 보장
     * - CREATE + TRUNCATE_EXISTING로 덮어쓰기
     */
    public void writeString(Path p, String text) {
        Objects.requireNonNull(p, "p");

        try {
            // 1) parent dir 생성
            mkdirs(p.getParent());

            // 2) UTF-8로 파일 저장
            Files.writeString(
                    p,
                    (text == null ? "" : text),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );

        } catch (IOException e) {
            throw new IllegalStateException("파일 저장 실패: " + p + ", err=" + e.getMessage(), e);
        }
    }
}
