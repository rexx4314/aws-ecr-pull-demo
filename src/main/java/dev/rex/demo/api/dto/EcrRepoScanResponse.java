package dev.rex.demo.api.dto;

import java.util.List;

public record EcrRepoScanResponse(
        int count,
        List<EcrRepoItem> items
) {
}
