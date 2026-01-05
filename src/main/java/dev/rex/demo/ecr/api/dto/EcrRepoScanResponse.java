package dev.rex.demo.ecr.api.dto;

import java.util.List;

/**
 * ECR 리포지토리 스캔 응답 DTO
 *
 * @param count 스캔 결과 항목 수
 * @param items  스캔된 리포지토리 항목들의 목록
 */
public record EcrRepoScanResponse(
        int count,
        List<EcrRepoItem> items
) {
}
