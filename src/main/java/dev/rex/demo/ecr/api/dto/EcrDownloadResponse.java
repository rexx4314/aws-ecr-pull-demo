package dev.rex.demo.ecr.api.dto;

/**
 * ECR 다운로드 결과 DTO
 * - resolvedTag/resolvedDigest: 서버가 최종 결정한 참조값
 * - outputPath/manifestPath/configPath: 저장 위치(문자열로 반환)
 */
public record EcrDownloadResponse(
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
