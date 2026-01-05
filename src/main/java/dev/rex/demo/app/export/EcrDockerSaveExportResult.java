package dev.rex.demo.app.export;

import java.nio.file.Path;

/**
 * docker-save 형식으로 내보낸 결과를 담는 DTO 레코드
 *
 * @param tarPath        생성된 docker-save tar 파일의 경로
 * @param sourceImageDir 원본 이미지가 저장된 디렉토리 경로
 */
public record EcrDockerSaveExportResult(
        Path tarPath,
        Path sourceImageDir
) {
}
