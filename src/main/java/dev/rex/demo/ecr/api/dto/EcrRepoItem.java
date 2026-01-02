package dev.rex.demo.ecr.api.dto;

import java.time.Instant;

/**
 * Repository 스캔 결과 항목
 * <p>
 * - latestTag: 최신 후보 태그(없으면 null)
 * - lastPushedAt: 최신 pushedAt (없으면 null)
 * - pullable: true면 다운로드 시도 가능
 * - reason: pullable=false 사유 코드(클라이언트 차단/분석용)
 */
public record EcrRepoItem(
        String repositoryName,
        String repositoryUri,
        String latestTag,
        Instant lastPushedAt,
        boolean pullable,
        String reason
) {
}
