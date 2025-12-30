package dev.rex.demo.api.dto;

import java.time.Instant;

public record EcrRepoItem(
        String repositoryName,
        String repositoryUri,
        String latestTag,
        Instant lastPushedAt,
        boolean pullable,
        String reason
) {
}
