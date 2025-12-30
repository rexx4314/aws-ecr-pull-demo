package dev.rex.demo.ecr;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.ecr.EcrClient;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class AwsEcrClientFactory {

    @Value("${demo.aws.ecr.api-call-attempt-timeout-seconds:10}")
    private long apiCallAttemptTimeoutSeconds;

    @Value("${demo.aws.ecr.api-call-timeout-seconds:15}")
    private long apiCallTimeoutSeconds;

    @Value("${demo.aws.ecr.max-retry-attempts:3}")
    private int maxRetryAttempts;

    public EcrClient create(String regionStr, String accessKeyId, String secretAccessKey) {
        Region region = Region.of(regionStr);
        AwsBasicCredentials creds = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

        ClientOverrideConfiguration override = ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(Duration.ofSeconds(Math.max(1, apiCallAttemptTimeoutSeconds)))
                .apiCallTimeout(Duration.ofSeconds(Math.max(1, apiCallTimeoutSeconds)))
                .retryStrategy(StandardRetryStrategy.builder()
                        .maxAttempts(Math.max(1, maxRetryAttempts))
                        .build())
                .build();

        return EcrClient.builder()
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(creds))
                .overrideConfiguration(override)
                .build();
    }
}
