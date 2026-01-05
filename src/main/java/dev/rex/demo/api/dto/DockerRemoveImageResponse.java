package dev.rex.demo.api.dto;

/**
 * Docker 이미지 삭제 응답을 나타내는 DTO 레코드
 *
 * @param imageRef 삭제된 Docker 이미지 참조값
 * @param removed  이미지 삭제 성공 여부
 * @param message  삭제 결과 메시지
 */
public record DockerRemoveImageResponse(
        String imageRef,
        boolean removed,
        String message
) {
}
