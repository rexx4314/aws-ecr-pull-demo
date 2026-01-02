package dev.rex.demo.ecr.api.dto;

import java.util.List;

/**
 * Repo 스캔 응답
 */
public record EcrRepoScanResponse(
        int count,
        List<EcrRepoItem> items
) {
}
