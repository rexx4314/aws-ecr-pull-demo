package dev.rex.demo.infra.tar;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * docker save 포맷의 tar 파일 생성기
 * <p>
 * 책임:
 * - 로컬 다운로드 산출물(imageDir/blobs/sha256/{hex})로부터 docker save 호환 tar 생성
 * - manifest.json / repositories / config / layer.tar 구조 구성
 * - 레이어 blob이 gzip이면 풀어서 layer.tar로 넣기
 * <p>
 * 주의/전제:
 * - TarArchiveEntry는 size가 필요
 * - gzip blob은 스트리밍으로 바로 tar에 넣으면 size를 알 수 없으므로 임시 파일로 풀어 size 확보 후 기록
 * <p>
 * - writeDockerSaveTar()는 오케스트레이션만 담당하고, 세부 로직은 private 메서드로 분리
 * - 입력 검증은 초기에 수행(guard clause)
 * - IOException 등 인프라 예외는 ApiException(ErrorCode)로 일관되게 래핑
 * - checked 예외(IOException)는 try 블록 내부에서만 발생하게 구성하여 "Unhandled exception"을 방지
 */
@Slf4j
@Component
public class DockerSaveTarWriter {

    /**
     * ObjectMapper는 (설정 변경 없이) thread-safe로 재사용 가능하므로 static 사용
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // docker save 표준 파일명들
    private static final String FILE_MANIFEST_JSON = "manifest.json";
    private static final String FILE_REPOSITORIES = "repositories";
    private static final String FILE_VERSION = "VERSION";
    private static final String FILE_JSON = "json";

    // docker save 레이어 메타(최소값)
    private static final byte[] VERSION_BYTES = "1.0\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EMPTY_JSON_BYTES = "{}\n".getBytes(StandardCharsets.UTF_8);

    /**
     * docker save tar 생성
     * <p>
     * 처리 흐름:
     * 1) 입력 검증 + 로컬 산출물(blobs/sha256) 존재 확인
     * 2) tarPath parent dir 생성
     * 3) TarArchiveOutputStream open
     * 4) manifest/repositories/config 생성 후 엔트리 기록
     * 5) layers 기록
     * <p>
     * 예외 정책:
     * - 비즈니스/정책 오류는 ApiException으로 throw
     * - I/O 오류는 DOWNLOAD_FS_WRITE_FAILED로 래핑
     */
    public void writeDockerSaveTar(Input in, Path tarPath) {
        // 0) 필수 입력 방어
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(tarPath, "tarPath");

        // 1) blobs/sha256 디렉토리 확인(다운로드 산출물이 있어야 export 가능)
        Path blobsDir = resolveBlobsSha256Dir(in.imageDir());
        requireDirectory(blobsDir, "blobs/sha256 디렉토리가 없습니다. 먼저 download를 수행하세요.");

        // 2) tar 파일이 생성될 parent 디렉토리를 보장
        mkdirsIfNeeded(tarPath.getParent());

        // 3) tar 쓰기(스트리밍)
        // - IOException이 발생 가능한 구간을 try 내부로 모아 컴파일 경고/Unhandled 문제를 예방
        try (OutputStream fos = Files.newOutputStream(
                tarPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {

            // 3-1) long file name(경로가 길 수 있음) 허용
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            // 3-2) config 파일명/내용 준비
            // - docker save tar의 Config 필드는 "<something>.json"이어야 함
            // - 현재 config.json로 고정
            String configFileName = resolveConfigFileName(in);
            byte[] configBytes = readConfigBytesOrThrow(in);

            // 3-3) manifest.json / repositories 바이트 생성
            // - ObjectMapper 직렬화는 IOException이 날 수 있으므로 try 내부에서 수행
            byte[] manifestBytes = buildDockerSaveManifestBytes(in.repoTag(), configFileName, in.layerDigests());
            byte[] repositoriesBytes = buildRepositoriesJsonBytes(in.repoTag());

            // 3-4) tar 엔트리 기록: manifest.json / repositories / config
            putBytesEntry(tos, FILE_MANIFEST_JSON, manifestBytes);
            putBytesEntry(tos, FILE_REPOSITORIES, repositoriesBytes);
            putBytesEntry(tos, configFileName, configBytes);

            // 3-5) layers 기록
            writeLayers(tos, blobsDir, in.layerDigests());

            // 3-6) 마무리
            tos.finish();

        } catch (ApiException ae) {
            // 이미 ErrorCode가 지정된 예외는 그대로 전파
            throw ae;

        } catch (IOException ioe) {
            // I/O 계열은 파일 쓰기 실패로 통일
            throw new ApiException(
                    ErrorCode.DOWNLOAD_FS_WRITE_FAILED,
                    "docker-save tar 생성 I/O 실패: " + safeMsg(ioe),
                    null,
                    ioe
            );

        } catch (Exception e) {
            // 그 외 예외는 내부 오류로 래핑(원인 포함)
            throw new ApiException(
                    ErrorCode.INTERNAL_ERROR,
                    "docker-save tar 생성 실패: " + safeMsg(e),
                    null,
                    e
            );
        }
    }

    /**
     * docker save manifest.json 바이트 생성
     * <p>
     * docker save manifest 예(최소):
     * [
     * {
     * "Config": "config.json",
     * "RepoTags": ["repo:tag"],
     * "Layers": ["{hex}/layer.tar", ...]
     * }
     * ]
     * <p>
     * 주의:
     * - ObjectMapper 직렬화는 IOException을 던질 수 있으므로 호출자는 try/catch 내부에서 호출해야 함
     */
    private byte[] buildDockerSaveManifestBytes(
            String repoTag,
            String configFileName,
            List<String> layerDigests
    ) throws IOException {
        // 1) 레이어 경로 배열 구성
        // - docker save는 각 레이어를 "{hex}/layer.tar" 형태로 참조
        List<String> layerPaths = new ArrayList<>(layerDigests.size());
        for (String dg : layerDigests) {
            String hex = DockerSaveNames.stripSha256Prefix(dg);
            layerPaths.add(hex + "/layer.tar");
        }

        // 2) docker save manifest 객체 구성
        // - Config: config json 파일명
        // - RepoTags: ["repo:tag"]
        // - Layers: ["{hex}/layer.tar", ...]
        List<Map<String, Object>> dockerSaveManifest = List.of(
                Map.of(
                        "Config", configFileName,
                        "RepoTags", List.of(repoTag),
                        "Layers", layerPaths
                )
        );

        // 3) JSON 직렬화
        return OBJECT_MAPPER.writeValueAsBytes(dockerSaveManifest);
    }

    /**
     * repositories 파일 바이트 생성
     * <p>
     * docker load에서 태그 복원을 도와주는 파일
     * 형식:
     * {
     * "repo": {
     * "tag": "local"
     * }
     * }
     * <p>
     * 정책:
     * - repositories가 없어도 docker load가 동작하는 케이스가 많으므로,
     * 직렬화 실패 시 "{}"로 폴백
     */
    private byte[] buildRepositoriesJsonBytes(String repoTag) {
        // 1) "repo:tag"를 파싱해서 repo/tag로 분리
        RepoTag parsed = parseRepoTag(repoTag);

        // 2) repositories JSON 구조 구성
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put(parsed.tag(), "local");
        root.put(parsed.repo(), tags);

        // 3) JSON 직렬화(실패하면 최소 JSON로 폴백)
        try {
            return OBJECT_MAPPER.writeValueAsBytes(root);
        } catch (Exception e) {
            return "{}\n".getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * docker save tar의 Config 엔트리 파일명 결정
     * <p>
     * 현재 프로젝트 정책:
     * - ECR config blob을 "config.json"로 저장하므로, tar에서도 동일 이름 사용
     */
    private String resolveConfigFileName(Input in) {
        return "config.json";
    }

    /**
     * 레이어 엔트리들을 tar에 기록
     * <p>
     * 각 레이어 디렉토리 구조(최소):
     * - {hex}/VERSION
     * - {hex}/json
     * - {hex}/layer.tar
     */
    private void writeLayers(TarArchiveOutputStream tos, Path blobsDir, List<String> layerDigests) throws IOException {
        for (String dg : layerDigests) {
            String hex = DockerSaveNames.stripSha256Prefix(dg);

            // 1) blob 파일 존재 확인
            Path blob = blobsDir.resolve(hex);
            if (!Files.isRegularFile(blob)) {
                // 로컬 산출물이 불완전하면 export를 진행할 수 없으므로 실패
                throw new ApiException(
                        ErrorCode.DOWNLOAD_TASK_FAILED,
                        "레이어 blob이 없습니다. digest=" + dg
                );
            }

            // 2) {hex}/VERSION (docker save 규격에서 요구)
            putBytesEntry(tos, hex + "/" + FILE_VERSION, VERSION_BYTES);

            // 3) {hex}/json (최소 메타: 빈 객체로도 동작하는 경우가 많음)
            putBytesEntry(tos, hex + "/" + FILE_JSON, EMPTY_JSON_BYTES);

            // 4) {hex}/layer.tar (실제 파일 시스템 payload)
            putLayerTarEntry(tos, hex + "/layer.tar", blob);
        }
    }

    /**
     * blob 파일을 docker-save 규격의 layer.tar 엔트리로 기록
     * <p>
     * 포인트:
     * - blob이 gzip일 수 있으므로 필요 시 decompress
     * - tar entry는 size가 필요하므로 임시 파일로 풀어 size 확보 후 기록
     */
    private void putLayerTarEntry(TarArchiveOutputStream tos, String entryName, Path blob) throws IOException {
        // 1) blob 스트림 오픈
        try (InputStream raw = Files.newInputStream(blob);
             BufferedInputStream bis = new BufferedInputStream(raw)) {

            // 2) gzip 여부 감지
            boolean gzip = isGzipStream(bis);

            // 3) payload 스트림 결정
            // - gzip이면 GZIPInputStream으로 풀고,
            // - 아니면 원본 그대로 사용
            InputStream payload = gzip ? new GZIPInputStream(bis) : bis;

            // 4) tar는 entry size가 필요하므로 임시 파일에 먼저 풀어 기록
            // - 성능/디스크 비용이 있지만, tar 규격상 size가 필수라 안전한 선택
            Path tmp = Files.createTempFile("layer-", ".tar");
            try {
                // 4-1) payload -> tmp 로 풀기
                try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
                    payload.transferTo(out);
                }

                // 4-2) tmp 크기를 entry size로 설정
                long size = Files.size(tmp);
                TarArchiveEntry entry = new TarArchiveEntry(entryName);
                entry.setSize(size);

                // 4-3) tar에 엔트리 기록
                tos.putArchiveEntry(entry);
                try (InputStream tin = Files.newInputStream(tmp)) {
                    tin.transferTo(tos);
                }
                tos.closeArchiveEntry();

            } finally {
                // 4-4) tmp 정리(실패해도 무시)
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * gzip 매직 넘버(1F 8B)로 gzip 여부 판별
     * <p>
     * 주의:
     * - BufferedInputStream의 mark/reset을 사용하므로 mark 가능한 스트림이어야 함
     */
    private boolean isGzipStream(BufferedInputStream in) throws IOException {
        in.mark(2);
        int b1 = in.read();
        int b2 = in.read();
        in.reset();
        return (b1 == 0x1f && b2 == 0x8b);
    }

    /**
     * config.json을 읽어 바이트로 반환
     * <p>
     * 정책:
     * - docker-save tar는 config가 필수
     * - includeConfig=false면 INVALID_REQUEST
     * - configPath가 없거나 파일이 없으면 DOWNLOAD_TASK_FAILED
     */
    private byte[] readConfigBytesOrThrow(Input in) {
        // 1) 정책: docker-save tar는 config 필수
        if (!in.includeConfig()) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "docker-save tar는 config가 필요합니다(includeConfig=true로 다운로드/export 하세요)."
            );
        }

        // 2) 파일 존재 확인
        Path p = in.configPath();
        if (p == null || !Files.isRegularFile(p)) {
            throw new ApiException(
                    ErrorCode.DOWNLOAD_TASK_FAILED,
                    "config.json이 없습니다. includeConfig=true로 다운로드했는지 확인하세요."
            );
        }

        // 3) 파일 읽기
        try {
            return Files.readAllBytes(p);
        } catch (IOException ioe) {
            throw new ApiException(
                    ErrorCode.DOWNLOAD_FS_WRITE_FAILED,
                    "config.json 읽기 실패: " + safeMsg(ioe),
                    null,
                    ioe
            );
        }
    }

    /**
     * 바이트 배열을 tar 엔트리로 기록
     * <p>
     * 주의:
     * - TarArchiveEntry는 size가 필요하므로 bytes.length를 setSize 로 지정해야 함
     */
    private void putBytesEntry(TarArchiveOutputStream tos, String name, byte[] bytes) throws IOException {
        Objects.requireNonNull(tos, "tos");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bytes, "bytes");

        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);

        tos.putArchiveEntry(entry);
        tos.write(bytes);
        tos.closeArchiveEntry();
    }

    /**
     * imageDir에서 blobs/sha256 디렉토리 경로를 계산
     */
    private Path resolveBlobsSha256Dir(Path imageDir) {
        return imageDir.resolve("blobs").resolve("sha256");
    }

    /**
     * 디렉토리 존재를 강제(없으면 ApiException)
     */
    private void requireDirectory(Path dir, String message) {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, message);
        }
    }

    /**
     * parent 디렉토리 생성(필요할 때만)
     */
    private void mkdirsIfNeeded(Path dir) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ApiException(
                    ErrorCode.DOWNLOAD_FS_WRITE_FAILED,
                    "디렉토리 생성 실패: " + dir + ", err=" + safeMsg(e),
                    null,
                    e
            );
        }
    }

    /**
     * "repo:tag" 문자열 파싱
     * <p>
     * 규칙:
     * - 마지막 ':' 기준 split
     * - tag가 없으면 latest 기본값
     * <p>
     * 참고:
     * - repoTag는 일반적으로 "repo:tag" 관례를 따른다고 가정
     */
    private RepoTag parseRepoTag(String repoTag) {
        String rt = StringUtils.trimToNull(repoTag);
        if (rt == null) {
            // repoTag가 null/blank여도 tar 생성 자체는 가능하게 하되 repositories는 최소값으로 구성
            return new RepoTag("_", "latest");
        }

        String repo = rt;
        String tag = "latest";

        int idx = rt.lastIndexOf(':');
        if (idx > 0 && idx < rt.length() - 1) {
            repo = rt.substring(0, idx);
            tag = rt.substring(idx + 1);
        }

        return new RepoTag(repo, tag);
    }

    /**
     * 예외 메시지 null-safe 변환
     */
    private String safeMsg(Throwable t) {
        if (t == null) return "(null)";
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.toString() : m;
    }

    /**
     * 입력 DTO
     * <p>
     * imageDir 구조 전제:
     * - {imageDir}/blobs/sha256/{layerHex}  (각 레이어 blob 파일)
     * <p>
     * 참고:
     * - ecrManifestPath, verifySha256는 현재 구현에서 직접 사용하지 않지만,
     * 상위 오케스트레이션에서 계약(구조)로 전달될 수 있어 Input에는 유지
     */
    public record Input(
            Path imageDir,
            Path ecrManifestPath,
            Path configPath,
            String repoTag,
            List<String> layerDigests,
            boolean includeConfig,
            boolean verifySha256
    ) {
        public Input {
            // 0) 필수값 방어: null이면 이후 로직이 의미 없으므로 즉시 실패
            Objects.requireNonNull(imageDir, "imageDir");
            Objects.requireNonNull(ecrManifestPath, "ecrManifestPath");
            Objects.requireNonNull(repoTag, "repoTag");
            Objects.requireNonNull(layerDigests, "layerDigests");

            // 1) 리스트는 불변으로 보관(호출자 변경 영향 제거)
            layerDigests = List.copyOf(layerDigests);
        }
    }

    private record RepoTag(String repo, String tag) {
    }
}
