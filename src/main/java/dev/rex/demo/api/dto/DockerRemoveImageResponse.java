package dev.rex.demo.api.dto;

public record DockerRemoveImageResponse(
        String imageRef,
        boolean removed,
        String message
) {
}
