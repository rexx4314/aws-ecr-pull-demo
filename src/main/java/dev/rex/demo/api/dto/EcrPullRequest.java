package dev.rex.demo.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record EcrPullRequest(
        @NotBlank String region,
        @NotBlank @Pattern(regexp = "^[0-9]{12}$", message = "accountId는 12자리 숫자여야 합니다") String accountId,
        @NotBlank String accessKeyId,
        @NotBlank String secretAccessKey,
        @NotBlank String repositoryName,

        String tag
) {
}
