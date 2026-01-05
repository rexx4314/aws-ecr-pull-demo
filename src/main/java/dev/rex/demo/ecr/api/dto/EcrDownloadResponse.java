package dev.rex.demo.ecr.api.dto;

/**
 * ECR 다운로드 결과 DTO
 * <p>
 * - resolvedTag / resolvedDigest: 서버가 최종 결정한 참조값
 * - layerCount: 총 레이어 수
 * - downloadedCount: 실제로 다운로드한 레이어 수
 * - outputPath / manifestPath / configPath: 저장된 경로(문자열)
 * - message: 처리 결과나 오류 메시지
 *
 * @param resolvedTag     서버가 결정한 태그 (없을 수 있음)
 * @param resolvedDigest  서버가 결정한 다이제스트 (없을 수 있음)
 * @param layerCount      총 레이어 개수
 * @param downloadedCount 다운로드된 레이어 개수
 * @param outputPath      출력 디렉터리 또는 파일 경로
 * @param manifestPath    매니페스트 파일 경로
 * @param configPath      이미지 config 파일 경로
 * @param message         추가 정보 또는 오류 메시지
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
