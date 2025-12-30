package dev.rex.demo.api.dto;

public record DockerHealthResponse(
        boolean ok,
        String message
) {
}
