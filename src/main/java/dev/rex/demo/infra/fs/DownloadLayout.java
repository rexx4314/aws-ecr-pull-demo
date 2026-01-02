package dev.rex.demo.infra.fs;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public class DownloadLayout {

    private final Path basePath;

    public DownloadLayout(Path rootDir, String accountId, String region, String repositoryName, String folderKey) {
        Objects.requireNonNull(rootDir, "rootDir");
        this.basePath = rootDir
                .resolve(safe(accountId))
                .resolve(safe(region))
                .resolve(repoAsPath(repositoryName))
                .resolve(safe(folderKey));

        mkdirs(this.basePath.resolve("blobs").resolve("sha256")); // blobs dir
        mkdirs(this.basePath.resolve("export").resolve("docker-save")); // export dir
    }

    public static void mkdirs(Path dir) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("디렉토리 생성 실패: " + dir + ", err=" + e.getMessage(), e);
        }
    }

    private static String digestHex(String digest) {
        String d = StringUtils.trimToNull(digest);
        if (d == null) throw new IllegalArgumentException("digest null");
        if (d.startsWith("sha256:")) return d.substring("sha256:".length());
        return d;
    }

    private static String safe(String s) {
        String t = StringUtils.trimToNull(s);
        if (t == null) return "_";
        return t.replace("..", "_").replace(":", "_"); // path sanitize
    }

    private static Path repoAsPath(String repo) {
        String t = StringUtils.trimToNull(repo);
        if (t == null) return Path.of("_");
        return Path.of(t); // allow nested
    }

    public Path basePath() {
        return basePath;
    }

    public Path manifestPath() {
        return basePath.resolve("manifest.json"); // ecr manifest
    }

    public Path configPath() {
        return basePath.resolve("config.json"); // config blob raw
    }

    public Path blobPathForDigest(String digest) {
        String hex = digestHex(digest);
        return basePath.resolve("blobs").resolve("sha256").resolve(hex);
    }

    public Path exportDockerSaveDir() {
        return basePath.resolve("export").resolve("docker-save"); // export docker-save
    }

    public Path exportDockerSaveTarPath(String fileName) {
        String fn = StringUtils.trimToNull(fileName);
        if (fn == null) fn = "image.tar";
        if (!fn.endsWith(".tar")) fn = fn + ".tar";
        Path dir = exportDockerSaveDir();
        mkdirs(dir);
        return dir.resolve(fn); // tar path
    }

    public void writeString(Path p, String text) {
        try {
            mkdirs(p.getParent());
            Files.writeString(p, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("파일 저장 실패: " + p + ", err=" + e.getMessage(), e);
        }
    }
}
