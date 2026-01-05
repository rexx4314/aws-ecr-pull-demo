package dev.rex.demo.api.dto;

import java.time.Instant;

/**
 * ECR 저장소 항목을 나타내는 DTO 레코드
 *
 * @param repositoryName ECR 저장소 이름
 * @param repositoryUri  ECR 저장소 URI (풀 시 사용)
 * @param latestTag      최신 이미지 태그
 * @param lastPushedAt   마지막 이미지 푸시 시간
 * @param pullable       이미지 풀 가능 여부
 * @param reason         풀 불가 사유 (pullable이 false일 때만 설정)
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
