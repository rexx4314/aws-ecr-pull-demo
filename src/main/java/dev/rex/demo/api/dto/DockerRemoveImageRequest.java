package dev.rex.demo.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * imageRef 예:
 * - 194356581254.dkr.ecr.ap-northeast-2.amazonaws.com/demo/rex-repo:v1
 * - 194356581254.dkr.ecr.ap-northeast-2.amazonaws.com/demo/rex-repo@sha256:...
 * <p>
 * 둘 다 docker rmi에 사용 가능
 */
public record DockerRemoveImageRequest(
        @NotBlank String imageRef,
        boolean force
) {
}
