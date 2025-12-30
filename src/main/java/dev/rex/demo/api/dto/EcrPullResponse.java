package dev.rex.demo.api.dto;

public record EcrPullResponse(
        String imageRef,
        String resolvedTag,
        String digest,
        String message
) {
}
