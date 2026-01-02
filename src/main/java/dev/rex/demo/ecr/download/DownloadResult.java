package dev.rex.demo.ecr.download;

public record DownloadResult(
        String resolvedTag,
        String resolvedDigest,
        int layerCount,
        int downloadedCount,
        String outputPath,
        String manifestPath,
        String configPath,
        String message
) {
}
