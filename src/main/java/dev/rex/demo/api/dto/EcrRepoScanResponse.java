package dev.rex.demo.api.dto;

import java.util.List;

/**
 * ECR 저장소 스캔 응답을 나타내는 DTO 레코드
 *
 * @param count ECR 저장소 총 개수
 * @param items ECR 저장소 항목 목록
 */
public record EcrRepoScanResponse(
        int count,
        List<EcrRepoItem> items
) {
}
