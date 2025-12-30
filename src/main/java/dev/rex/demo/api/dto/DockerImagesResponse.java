package dev.rex.demo.api.dto;

import java.util.List;

public record DockerImagesResponse(
        int count,
        List<DockerImageItem> items
) {
}
