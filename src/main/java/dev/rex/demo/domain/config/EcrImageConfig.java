package dev.rex.demo.domain.config;

import java.util.List;

/**
 * 이미지 config 모델
 */
public record EcrImageConfig(
        List<String> diffIds,
        String created
) {
}
