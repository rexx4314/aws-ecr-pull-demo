package dev.rex.demo.infra.tar;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
public class DockerSaveTarWriter {

    private static final ObjectMapper om = new ObjectMapper();

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
            Objects.requireNonNull(imageDir, "imageDir");
            Objects.requireNonNull(ecrManifestPath, "ecrManifestPath");
            Objects.requireNonNull(repoTag, "repoTag");
            Objects.requireNonNull(layerDigests, "layerDigests");
            layerDigests = List.copyOf(layerDigests);
        }
    }

    public void writeDockerSaveTar(Input in, Path tarPath) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(tarPath, "tarPath");

        Path blobsDir = in.imageDir().resolve("blobs").resolve("sha256");
        if (!Files.isDirectory(blobsDir)) {
            // 로컬 다운로드 산출물 없음/작업 순서 문제
            throw new ApiException(
                    ErrorCode.DOWNLOAD_TASK_FAILED,
                    "blobs/sha256 디렉토리가 없습니다. 먼저 download를 수행하세요."
            );
        }

        String configFileName = resolveConfigFileName(in);
        byte[] configBytes = readConfigBytesIfNeeded(in);

        List<String> layerPaths = new ArrayList<>();
        for (String dg : in.layerDigests()) {
            String hex = DockerSaveNames.stripSha256Prefix(dg);
            layerPaths.add(hex + "/layer.tar");
        }

        List<Map<String, Object>> dockerSaveManifest = List.of(Map.of(
                "Config", configFileName,
                "RepoTags", List.of(in.repoTag()),
                "Layers", layerPaths
        ));

        Path parent = tarPath.getParent();
        if (parent != null) mkdirs(parent);

        try (OutputStream fos = Files.newOutputStream(tarPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             TarArchiveOutputStream tos = new TarArchiveOutputStream(bos)) {

            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            // manifest.json
            byte[] manifestBytes = om.writeValueAsBytes(dockerSaveManifest);
            putBytes(tos, "manifest.json", manifestBytes);

            // repositories (태그 보조)
            putBytes(tos, "repositories", buildRepositoriesJson(in.repoTag(), configFileName));

            // config
            putBytes(tos, configFileName, configBytes);

            // layers
            for (String dg : in.layerDigests()) {
                String hex = DockerSaveNames.stripSha256Prefix(dg);
                Path blob = blobsDir.resolve(hex);
                if (!Files.isRegularFile(blob)) {
                    // blob 누락(로컬 산출물 불완전)
                    throw new ApiException(
                            ErrorCode.DOWNLOAD_TASK_FAILED,
                            "레이어 blob이 없습니다. digest=" + dg
                    );
                }

                // VERSION
                putBytes(tos, hex + "/VERSION", "1.0\n".getBytes(StandardCharsets.UTF_8));

                // json (최소)
                putBytes(tos, hex + "/json", "{}\n".getBytes(StandardCharsets.UTF_8));

                // layer.tar
                putLayerTar(tos, hex + "/layer.tar", blob);
            }

            tos.finish();

        } catch (ApiException ae) {
            throw ae;
        } catch (IOException ioe) {
            throw new ApiException(
                    ErrorCode.DOWNLOAD_FS_WRITE_FAILED,
                    "docker-save tar 생성 I/O 실패: " + ioe.getMessage(),
                    null,
                    ioe
            );
        } catch (Exception e) {
            throw new ApiException(
                    ErrorCode.INTERNAL_ERROR,
                    "docker-save tar 생성 실패: " + (e.getMessage() == null ? e.toString() : e.getMessage()),
                    null,
                    e
            );
        }
    }

    private String resolveConfigFileName(Input in) {
        // docker save tar의 Config는 "<something>.json"
        // 여기서는 ECR config blob을 "config.json"로 저장했으므로, 호환을 위해 고정 파일명 사용
        return "config.json";
    }

    private byte[] readConfigBytesIfNeeded(Input in) {
        if (!in.includeConfig()) {
            // export 정책/요청 제약
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "docker-save tar는 config가 필요합니다(includeConfig=true로 다운로드/export 하세요)."
            );
        }

        Path p = in.configPath();
        if (p == null || !Files.isRegularFile(p)) {
            throw new ApiException(
                    ErrorCode.DOWNLOAD_TASK_FAILED,
                    "config.json이 없습니다. includeConfig=true로 다운로드했는지 확인하세요."
            );
        }

        try {
            return Files.readAllBytes(p);
        } catch (IOException ioe) {
            throw new ApiException(
                    ErrorCode.DOWNLOAD_FS_WRITE_FAILED,
                    "config.json 읽기 실패: " + ioe.getMessage(),
                    null,
                    ioe
            );
        }
    }

    private byte[] buildRepositoriesJson(String repoTag, String configFileName) {
        // repositories 파일은 docker load에서 태그 복원을 도와줌
        // 형식: { "repo": { "tag": "someid" } }
        String repo = repoTag;
        String tag = "latest";
        int idx = repoTag.lastIndexOf(':');
        if (idx > 0 && idx < repoTag.length() - 1) {
            repo = repoTag.substring(0, idx);
            tag = repoTag.substring(idx + 1);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put(tag, "local");
        root.put(repo, tags);

        try {
            return om.writeValueAsBytes(root);
        } catch (Exception e) {
            return "{}\n".getBytes(StandardCharsets.UTF_8);
        }
    }

    private void putLayerTar(TarArchiveOutputStream tos, String entryName, Path blob) throws IOException {
        // ECR layer blob은 gzip일 수 있음
        try (InputStream raw = Files.newInputStream(blob);
             BufferedInputStream bis = new BufferedInputStream(raw)) {

            bis.mark(4);
            boolean gzip = isGzip(bis);
            bis.reset();

            InputStream payload = gzip ? new java.util.zip.GZIPInputStream(bis) : bis;

            // tar는 size가 필요하므로 임시파일로 풀어서 size 확보
            Path tmp = Files.createTempFile("layer-", ".tar");
            try {
                try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
                    payload.transferTo(out);
                }

                long size = Files.size(tmp);

                TarArchiveEntry e = new TarArchiveEntry(entryName);
                e.setSize(size);
                tos.putArchiveEntry(e);

                try (InputStream tin = Files.newInputStream(tmp)) {
                    tin.transferTo(tos);
                }

                tos.closeArchiveEntry();
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
    }

    private boolean isGzip(BufferedInputStream in) throws IOException {
        in.mark(2);
        int b1 = in.read();
        int b2 = in.read();
        in.reset();
        return b1 == 0x1f && b2 == 0x8b;
    }

    private void putBytes(TarArchiveOutputStream tos, String name, byte[] bytes) throws IOException {
        TarArchiveEntry e = new TarArchiveEntry(name);
        e.setSize(bytes.length);
        tos.putArchiveEntry(e);
        tos.write(bytes);
        tos.closeArchiveEntry();
    }

    private void mkdirs(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ApiException(
                    ErrorCode.DOWNLOAD_FS_WRITE_FAILED,
                    "디렉토리 생성 실패: " + dir + ", err=" + e.getMessage(),
                    null,
                    e
            );
        }
    }

    // 로깅용 (기존 코드 유지)
    private static final class Slf4j {
        static void debug(String msg, Object... args) {}
    }
}
