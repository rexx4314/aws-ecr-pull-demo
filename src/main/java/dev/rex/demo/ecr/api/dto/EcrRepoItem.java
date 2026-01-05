package dev.rex.demo.ecr.api.dto;

import java.time.Instant;

/**
 * ECR 저장소 스캔 결과 항목을 나타내는 DTO 레코드
 * <p>
 * - latestTag: 최신 후보 태그(없으면 null)
 * - lastPushedAt: 마지막으로 푸시된 시각(없으면 null)
 * - pullable: true면 다운로드 시도 가능
 * - reason: pullable=false인 경우 원인 코드나 설명(클라이언트 차단/분석용)
 *
 * @param repositoryName 저장소 이름
 * @param repositoryUri  저장소 URI
 * @param latestTag      최신 태그 후보 (없을 수 있음)
 * @param lastPushedAt   마지막 푸시 시각 (없을 수 있음)
 * @param pullable       다운로드 가능 여부
 * @param reason         pullable=false일 때 사유 설명 또는 코드
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
