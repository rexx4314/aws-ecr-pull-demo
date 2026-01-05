package dev.rex.demo.app.download;

/**
 * 다운로드 결과 요약 DTO.
 * <p>
 * 전체 다운로드 작업 완료 후 반환되는 최종 결과를 담음.
 * <p>
 * 포함 정보:
 * - 확인된 태그/다이제스트 (다운로드된 이미지 식별자)
 * - 레이어 다운로드 통계 (전체 수 및 성공 수)
 * - 출력 경로 정보 (루트, 매니페스트, 설정)
 * - 사용자 메시지 (부분 성공 등)
 *
 * @param resolvedTag     최종 확인된 태그 (예: latest, v1.0.0)
 * @param resolvedDigest  최종 확인된 다이제스트 (예: sha256:abc123...)
 * @param layerCount      매니페스트에 포함된 전체 레이어 수
 * @param downloadedCount 실제로 다운로드에 성공한 레이어 수
 * @param outputPath      다운로드 루트 경로
 * @param manifestPath    저장된 매니페스트 파일 경로
 * @param configPath      설정 파일 경로 (다운로드 안 했으면 null)
 * @param message         사용자에게 표시할 간단한 상태 메시지
 */
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
