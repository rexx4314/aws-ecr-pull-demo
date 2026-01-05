package dev.rex.demo.api.dto;

/**
 * Docker 이미지 정보를 나타내는 DTO 레코드
 * {@code docker images --digests} 명령 결과의 한 항목을 표현
 *
 * @param repository   이미지 저장소 이름
 * @param tag          이미지 태그
 * @param digest       이미지 다이제스트 (SHA256 해시)
 * @param imageId      이미지 ID
 * @param createdSince 생성 시간 (상대 시간)
 * @param size         이미지 크기
 */
public record DockerImageItem(
        String repository,
        String tag,
        String digest,
        String imageId,
        String createdSince,
        String size
) {
}
