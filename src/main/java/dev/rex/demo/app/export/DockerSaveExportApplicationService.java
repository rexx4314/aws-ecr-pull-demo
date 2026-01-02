package dev.rex.demo.app.export;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.ecr.api.dto.EcrDockerSaveExportRequest;
import dev.rex.demo.infra.fs.DownloadLayout;
import dev.rex.demo.domain.manifest.ManifestParser;
import dev.rex.demo.domain.manifest.ParsedManifest;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerSaveExportApplicationService {

    private final DockerSaveTarWriter tarWriter;

    @Value("${app.download.baseDir:./out}")
    private String defaultBaseDir;

    public EcrDockerSaveExportResult exportDockerSaveTarFromLocal(EcrDockerSaveExportRequest req) {
        Objects.requireNonNull(req, "req");

        if (req.resolveLatest()) {
            // 정책상 지원하지 않는 옵션
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "export는 로컬 산출물 기반입니다. resolveLatest=true는 지원하지 않습니다. 먼저 download로 tag를 확정하세요."
            );
        }

        String outputDir = StringUtils.defaultIfBlank(req.outputDir(), defaultBaseDir);

        ResolvedRef ref = resolveRef(req);
        DownloadLayout layout = new DownloadLayout(
                Path.of(outputDir),
                req.accountId(),
                req.region(),
                req.repositoryName(),
                ref.folderKey()
        );

        Path imageDir = layout.basePath();
        Path ecrManifestPath = layout.manifestPath();
        Path configPath = layout.configPath();

        requireFile(ecrManifestPath, "manifest.json이 없습니다. 먼저 /api/ecr/download를 호출하세요.");
        if (req.includeConfig()) {
            requireFile(configPath, "config.json이 없습니다. includeConfig=true로 다운로드했는지 확인하세요.");
        }

        String ecrManifestJson;
        try {
            ecrManifestJson = Files.readString(ecrManifestPath);
        } catch (IOException ioe) {
            throw new ApiException(
                    ErrorCode.DOWNLOAD_FS_WRITE_FAILED,
                    "manifest.json 읽기 실패: " + ioe.getMessage(),
                    null,
                    ioe
            );
        }

        ParsedManifest parsed = ManifestParser.parse(ecrManifestJson);
        if (parsed.isManifestList()) {
            // 기능 미지원/요청 제약
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "멀티 아키텍처(manifest list)는 export 미지원입니다. platform 선택 기능이 필요합니다."
            );
        }

        List<String> layerDigests = parsed.layerDigests();
        if (layerDigests == null || layerDigests.isEmpty()) {
            throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "manifest에 layers가 없습니다.");
        }

        String repoTag = StringUtils.trimToNull(req.repoTag());
        if (repoTag == null) {
            String tag = StringUtils.trimToNull(req.tag());
            if (tag != null) repoTag = req.repositoryName() + ":" + tag;
        }
        if (repoTag == null) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST,
                    "repoTag가 비었습니다. tag 기반 export는 repoTag를 자동 구성할 수 있어야 합니다."
            );
        }

        String tarFileName = DockerSaveNames.safeTarFileName(
                StringUtils.defaultIfBlank(req.fileNameHint(), "docker-save_" + repoTag + ".tar")
        );

        Path exportDir = imageDir.resolve("export").resolve("docker-save");
        mkdirs(exportDir);

        Path tarPath = exportDir.resolve(tarFileName);

        DockerSaveTarWriter.Input input = new DockerSaveTarWriter.Input(
                imageDir,
                ecrManifestPath,
                configPath,
                repoTag,
                layerDigests,
                req.includeConfig(),
                req.verifySha256()
        );

        tarWriter.writeDockerSaveTar(input, tarPath);

        log.info("docker-save tar created. tarPath={}, imageDir={}", tarPath.toAbsolutePath(), imageDir.toAbsolutePath());
        return new EcrDockerSaveExportResult(tarPath, imageDir);
    }

    private ResolvedRef resolveRef(EcrDockerSaveExportRequest req) {
        String tag = StringUtils.trimToNull(req.tag());
        String digest = StringUtils.trimToNull(req.digest());

        if (digest != null) {
            return ResolvedRef.byDigest(digest);
        }
        if (tag != null) {
            return ResolvedRef.byTag(tag);
        }

        throw new ApiException(ErrorCode.INVALID_REQUEST, "tag 또는 digest 중 하나가 필요합니다.");
    }

    private void requireFile(Path p, String msg) {
        if (p == null || !Files.isRegularFile(p)) {
            // “로컬 산출물이 없음”은 보통 작업 순서 문제/산출물 미생성
            throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, msg);
        }
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

    private record ResolvedRef(boolean digest, String tag, String digestValue) {
        static ResolvedRef byTag(String tag) {
            return new ResolvedRef(false, tag, null);
        }

        static ResolvedRef byDigest(String digest) {
            return new ResolvedRef(true, null, digest);
        }

        String folderKey() {
            if (digest) {
                return "digest-" + DockerSaveNames.stripSha256Prefix(digestValue);
            }
            return "tag-" + tag;
        }
    }
}
