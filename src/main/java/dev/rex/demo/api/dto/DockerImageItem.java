package dev.rex.demo.api.dto;

/**
 * docker images --digests 결과 1건
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
