package dev.rex.demo.app.download;

/**
 * 다운로드 결과 요약.
 */
public record DownloadResult(
        String resolvedTag,     // 최종 태그
        String resolvedDigest,  // 최종 다이제스트
        int layerCount,         // 전체 레이어 수
        int downloadedCount,    // 다운로드 성공 수
        String outputPath,      // 출력 루트 경로
        String manifestPath,    // 매니페스트 파일 경로
        String configPath,      // config 파일 경로
        String message          // 사용자 메시지
) {
}
