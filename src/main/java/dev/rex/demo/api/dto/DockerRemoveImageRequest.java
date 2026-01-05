package dev.rex.demo.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Docker 이미지 삭제 요청을 나타내는 DTO 레코드
 *
 * @param imageRef Docker 이미지 참조값 (저장소명:태그 또는 저장소명@다이제스트 형식)
 *                 예: 194356581254.dkr.ecr.ap-northeast-2.amazonaws.com/demo/rex-repo:v1
 *                 또는 194356581254.dkr.ecr.ap-northeast-2.amazonaws.com/demo/rex-repo@sha256:...
 * @param force    강제 삭제 여부 (true 면 사용 중인 이미지도 삭제)
 */
public record DockerRemoveImageRequest(
        @NotBlank String imageRef,
        boolean force
) {
}
