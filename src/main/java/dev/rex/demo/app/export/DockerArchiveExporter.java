package dev.rex.demo.app.export;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.common.util.Masking;
import dev.rex.demo.domain.config.EcrImageConfig;
import dev.rex.demo.domain.config.EcrImageConfigParser;
import dev.rex.demo.domain.image.DockerChainId;
import dev.rex.demo.domain.image.ImageRefResolver;
import dev.rex.demo.domain.image.LayerTransformer;
import dev.rex.demo.domain.image.PreparedLayer;
import dev.rex.demo.domain.manifest.DockerSaveManifestBuilder;
import dev.rex.demo.ecr.api.dto.EcrExportDockerArchiveRequest;
import dev.rex.demo.infra.aws.EcrBlobFileFetcher;
import dev.rex.demo.infra.aws.EcrClientFactory;
import dev.rex.demo.domain.manifest.ManifestParser;
import dev.rex.demo.domain.manifest.ParsedManifest;
import dev.rex.demo.infra.aws.ManifestFetcher;
import dev.rex.demo.infra.tar.TarStreamWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ecr.EcrClient;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * docker save tar(export) 서비스
 * - config 강제 포함
 * - gzip 레이어를 비압축 tar로 변환
 * - diff_id 검증
 * - 대용량은 임시파일 스트리밍 처리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DockerArchiveExporter {

    // 기본 제한
    private static final int DEFAULT_HTTP_TIMEOUT_SEC = 180;
    private static final int DEFAULT_MAX_RETRIES = 4;
    private static final int DEFAULT_MAX_PAGES = 50;
    private static final int DEFAULT_MAX_IMAGES = 2000;

    private final EcrClientFactory ecrClientFactory;
    private final ImageRefResolver imageRefResolver;
    private final ManifestFetcher manifestFetcher;
    private final EcrBlobFileFetcher ecrBlobFileFetcher;

    public void exportDockerSaveTar(EcrExportDockerArchiveRequest req, OutputStream out) {
        Objects.requireNonNull(req, "req");
        Objects.requireNonNull(out, "out");

        int httpTimeoutSec = (req.httpTimeoutSeconds() == null || req.httpTimeoutSeconds() <= 0)
                ? DEFAULT_HTTP_TIMEOUT_SEC : req.httpTimeoutSeconds();
        int maxRetries = (req.maxRetries() == null || req.maxRetries() <= 0)
                ? DEFAULT_MAX_RETRIES : req.maxRetries();
        int maxPages = (req.maxPages() == null || req.maxPages() <= 0) ? DEFAULT_MAX_PAGES : req.maxPages();
        int maxImages = (req.maxImages() == null || req.maxImages() <= 0) ? DEFAULT_MAX_IMAGES : req.maxImages();

        // 임시 작업 폴더
        Path workDir = createTempDir("docker-save-export-");
        try (EcrClient ecr = ecrClientFactory.create(req.region(), req.accessKeyId(), req.secretAccessKey(), req.sessionToken())) {

            // 1) 이미지 ref 결정
            ImageRefResolver.ResolvedImageRef ref = imageRefResolver.resolve(
                    ecr,
                    req.accountId(),
                    req.repositoryName(),
                    req.resolveLatest(),
                    req.tag(),
                    req.digest(),
                    maxPages,
                    maxImages
            );

            // 2) manifest JSON 조회
            String manifestJson = manifestFetcher.fetchManifestJson(ecr, req.accountId(), req.repositoryName(), ref);

            // 3) manifest 파싱
            ParsedManifest parsed = ManifestParser.parse(manifestJson);
            if (parsed.isManifestList()) {
                // 기능 미지원/요청 제약
                throw new ApiException(
                        ErrorCode.INVALID_REQUEST,
                        "Manifest list(멀티 아키텍처)는 미지원입니다. docker-save export도 불가합니다."
                );
            }

            List<String> layerDigests = (parsed.layerDigests() == null) ? List.of() : parsed.layerDigests();
            String configDigest = StringUtils.trimToNull(parsed.configDigest());

            if (layerDigests.isEmpty()) {
                // 데이터 비정상
                throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "Manifest에 layers가 없습니다(비정상 manifest).");
            }
            if (configDigest == null) {
                // export는 config 강제 포함
                throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "Manifest에 config.digest가 없습니다(export 불가).");
            }

            // 4) config 강제 포함(blob)
            Path configBlob = workDir.resolve("config.blob");
            ecrBlobFileFetcher.fetchBlobToFile(
                    ecr, req.accountId(), req.repositoryName(), configDigest,
                    configBlob, httpTimeoutSec, maxRetries, true
            );

            // 5) config 파싱 + diff_ids 확보
            byte[] configBytes;
            try {
                configBytes = Files.readAllBytes(configBlob);
            } catch (IOException ioe) {
                throw new ApiException(
                        ErrorCode.DOWNLOAD_FS_WRITE_FAILED,
                        "config.blob 읽기 실패: " + ioe.getMessage(),
                        null,
                        ioe
                );
            }

            EcrImageConfig cfg = EcrImageConfigParser.parse(configBytes);

            List<String> diffIds = cfg.diffIds();
            if (diffIds == null || diffIds.isEmpty()) {
                throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "config.rootfs.diff_ids가 비었습니다(export 불가).");
            }
            if (diffIds.size() != layerDigests.size()) {
                throw new ApiException(
                        ErrorCode.DOWNLOAD_TASK_FAILED,
                        "layer 개수와 diff_ids 개수가 다릅니다. layers=" + layerDigests.size() + ", diffIds=" + diffIds.size()
                );
            }

            // 6) imageId 계산
            String imageIdHex = sha256Hex(configBytes);

            // 7) 레이어 처리
            List<PreparedLayer> prepared = new ArrayList<>(layerDigests.size());
            String parentChainId = null; // "sha256:<hex>"

            for (int i = 0; i < layerDigests.size(); i++) {
                String layerDigest = StringUtils.trimToNull(layerDigests.get(i));
                if (layerDigest == null) {
                    throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "layer digest가 null입니다. index=" + i);
                }

                String expectedDiffId = normalizeSha256(diffIds.get(i));
                if (expectedDiffId == null) {
                    throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "diff_id가 null입니다. index=" + i);
                }

                // 7-1) blob 다운로드
                Path layerBlob = workDir.resolve("layer-" + i + ".blob");
                ecrBlobFileFetcher.fetchBlobToFile(
                        ecr, req.accountId(), req.repositoryName(), layerDigest,
                        layerBlob, httpTimeoutSec, maxRetries, true
                );

                // 7-2) 비압축 tar 생성 + diff_id 계산
                Path layerTar = workDir.resolve("layer-" + i + ".tar");
                String computedDiffId = LayerTransformer.toUncompressedTarAndDiffId(layerBlob, layerTar);

                if (!normalizeSha256(expectedDiffId).equalsIgnoreCase(normalizeSha256(computedDiffId))) {
                    // 무결성 오류
                    throw new ApiException(
                            ErrorCode.DOWNLOAD_DIGEST_MISMATCH,
                            "diff_id 불일치. index=" + i +
                                    ", expected=" + expectedDiffId +
                                    ", actual=" + computedDiffId +
                                    ", layerDigest=" + Masking.maskDigest(layerDigest),
                            null
                    );
                }

                // 7-3) chainId/layerId 생성
                String layerIdHex = DockerChainId.chainIdHex(parentChainId, expectedDiffId);
                String chainIdSha = "sha256:" + layerIdHex;
                parentChainId = chainIdSha;

                prepared.add(new PreparedLayer(i, layerIdHex, chainIdSha, layerTar, expectedDiffId));
            }

            // 8) docker-archive tar 작성
            writeDockerArchiveTar(out, req, imageIdHex, configBytes, prepared);

        } catch (ApiException ae) {
            throw ae;
        } catch (Exception e) {
            throw new ApiException(
                    ErrorCode.INTERNAL_ERROR,
                    "docker-save export 실패: " + (e.getMessage() == null ? e.toString() : e.getMessage()),
                    null,
                    e
            );
        } finally {
            deleteRecursivelyQuiet(workDir);
        }
    }

    private void writeDockerArchiveTar(
            OutputStream out,
            EcrExportDockerArchiveRequest req,
            String imageIdHex,
            byte[] configBytes,
            List<PreparedLayer> layers
    ) throws IOException {

        String repoTag = req.effectiveRepoTag();

        String rootManifestJson = DockerSaveManifestBuilder.buildManifestJson(imageIdHex, repoTag, layers);
        String repositoriesJson = DockerSaveManifestBuilder.buildRepositoriesJson(imageIdHex, repoTag);

        try (TarStreamWriter tw = new TarStreamWriter(out)) {

            tw.putBytes("manifest.json", rootManifestJson.getBytes(StandardCharsets.UTF_8));
            tw.putBytes("repositories", repositoriesJson.getBytes(StandardCharsets.UTF_8));
            tw.putBytes(imageIdHex + ".json", configBytes);

            for (int i = 0; i < layers.size(); i++) {
                PreparedLayer pl = layers.get(i);

                tw.putFile(pl.layerIdHex() + "/layer.tar", pl.layerTarPath());
                tw.putBytes(pl.layerIdHex() + "/VERSION", "1.0\n".getBytes(StandardCharsets.UTF_8));

                String parentHex = (i == 0) ? null : layers.get(i - 1).layerIdHex();
                String layerJson = DockerSaveManifestBuilder.buildLayerJson(pl.layerIdHex(), parentHex, Instant.now().toString());
                tw.putBytes(pl.layerIdHex() + "/json", layerJson.getBytes(StandardCharsets.UTF_8));
            }

            tw.finish();
        }
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(bytes);
            return toHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 계산 실패: " + e.getMessage(), e);
        }
    }

    private String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            int v = x & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private String normalizeSha256(String s) {
        String t = StringUtils.trimToNull(s);
        if (t == null) return null;
        return t.startsWith("sha256:") ? t : ("sha256:" + t);
    }

    private Path createTempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new ApiException(
                    ErrorCode.DOWNLOAD_FS_WRITE_FAILED,
                    "임시 디렉토리 생성 실패: " + e.getMessage(),
                    null,
                    e
            );
        }
    }

    private void deleteRecursivelyQuiet(Path dir) {
        if (dir == null) return;
        try {
            if (!Files.exists(dir)) return;
            Files.walk(dir)
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }
}
