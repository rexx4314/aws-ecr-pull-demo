package dev.rex.demo.ecr.download;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * 다운로드 저장 레이아웃
 * - root/account/region/repo/folderKey/...
 * - blobs/sha256/<hex>
 */
public class DownloadLayout {

    private final Path basePath;

    public DownloadLayout(Path rootDir, String accountId, String region, String repositoryName, String folderKey) {
        Objects.requireNonNull(rootDir, "rootDir");

        // 경로 조립
        this.basePath = rootDir
                .resolve(safe(accountId))
                .resolve(safe(region))
                .resolve(repoAsPath(repositoryName))
                .resolve(safe(folderKey));

        // 기본 폴더 생성
        mkdirs(this.basePath.resolve("blobs").resolve("sha256"));
    }

    // 디렉토리 생성
    public static void mkdirs(Path dir) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("디렉토리 생성 실패: " + dir + ", err=" + e.getMessage(), e);
        }
    }

    // digest -> hex
    private static String digestHex(String digest) {
        String d = StringUtils.trimToNull(digest);
        if (d == null) throw new IllegalArgumentException("digest null");
        if (d.startsWith("sha256:")) return d.substring("sha256:".length());
        return d;
    }

    // 경로 안전 보정
    private static String safe(String s) {
        String t = StringUtils.trimToNull(s);
        if (t == null) return "_";
        return t.replace("..", "_").replace(":", "_");
    }

    // repo "/" 지원
    private static Path repoAsPath(String repo) {
        String t = StringUtils.trimToNull(repo);
        if (t == null) return Path.of("_");
        return Path.of(t);
    }

    public Path basePath() {
        return basePath;
    }

    public Path manifestPath() {
        return basePath.resolve("manifest.json");
    }

    public Path configPath() {
        // config blob 원문 저장
        return basePath.resolve("config.json");
    }

    public Path blobPathForDigest(String digest) {
        String hex = digestHex(digest);
        return basePath.resolve("blobs").resolve("sha256").resolve(hex);
    }

    // 문자열 파일 저장
    public void writeString(Path p, String text) {
        try {
            mkdirs(p.getParent());
            Files.writeString(
                    p,
                    (text == null) ? "" : text,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new IllegalStateException("파일 저장 실패: " + p + ", err=" + e.getMessage(), e);
        }
    }
}
