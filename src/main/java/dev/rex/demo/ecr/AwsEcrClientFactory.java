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

/**
 * AWS ECR 클라이언트 팩토리
 * <p>
 * - AWS ECR SDK 클라이언트 생성 전담
 * - 타임아웃, 재시도 정책 등 공통 설정 적용
 * - application.yml의 설정값 기반으로 클라이언트 구성
 */
@Component
@RequiredArgsConstructor
public class AwsEcrClientFactory {

    /**
     * 단일 API 호출 시도 타임아웃 (초)
     */
    @Value("${demo.aws.ecr.api-call-attempt-timeout-seconds:10}")
    private long apiCallAttemptTimeoutSeconds;

    /**
     * 전체 API 호출 타임아웃 (초)
     */
    @Value("${demo.aws.ecr.api-call-timeout-seconds:15}")
    private long apiCallTimeoutSeconds;

    /**
     * 최대 재시도 횟수
     */
    @Value("${demo.aws.ecr.max-retry-attempts:3}")
    private int maxRetryAttempts;

    /**
     * ECR 클라이언트 생성
     *
     * @param regionStr       AWS 리전 (예: ap-northeast-2)
     * @param accessKeyId     AWS 액세스 키 ID
     * @param secretAccessKey AWS 시크릿 액세스 키
     * @return 설정된 ECR 클라이언트
     */
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
