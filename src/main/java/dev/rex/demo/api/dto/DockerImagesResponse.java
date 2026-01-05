package dev.rex.demo.api.dto;

import java.util.List;

/**
 * Docker 이미지 목록 조회 응답을 나타내는 DTO 레코드
 *
 * @param count Docker 이미지 개수
 * @param items Docker 이미지 항목 목록
 */
public record DockerImagesResponse(
        int count,
        List<DockerImageItem> items
) {
}
