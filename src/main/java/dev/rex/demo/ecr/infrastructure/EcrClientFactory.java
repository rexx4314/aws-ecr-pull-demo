package dev.rex.demo.ecr.infrastructure;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.ecr.EcrClient;

import java.time.Duration;

/**
 * AWS ECR 서비스와 통신할 수 있는 클라이언트 객체 생성
 */
@Component
public class EcrClientFactory {

    // 1회 API 호출 시 허용되는 최대 시간 (기본값: 10초)
    @Value("${demo.aws.ecr.api-call-attempt-timeout-seconds:10}")
    private long apiCallAttemptTimeoutSeconds;

    // 전체 API 호출 과정(재시도 포함)에 허용되는 총 시간 (기본값: 15초)
    @Value("${demo.aws.ecr.api-call-timeout-seconds:15}")
    private long apiCallTimeoutSeconds;

    // 네트워크 문제 등으로 실패했을 때 다시 시도할 최대 횟수 (기본값: 3회)
    @Value("${demo.aws.ecr.max-retry-attempts:3}")
    private int maxRetryAttempts;

    /**
     * 제공된 인증 정보를 사용하여 ECR 클라이언트 생성
     *
     * @param regionStr       접속할 AWS 지역 (예: "ap-northeast-2")
     * @param accessKeyId     AWS 액세스 키 ID
     * @param secretAccessKey AWS 비밀 액세스 키
     * @param sessionToken    임시 보안 자격 증명 세션 토큰 (선택 사항)
     * @return 설정이 완료된 EcrClient 객체
     */
    public EcrClient create(String regionStr, String accessKeyId, String secretAccessKey, String sessionToken) {
        // AWS region 정보를 객체로 변환
        Region region = Region.of(regionStr);

        // 1. 인증 방식 결정: 세션 토큰 유무에 따라 임시 자격 증명 또는 기본 자격 증명 사용
        AwsCredentialsProvider provider;
        if (StringUtils.isNotBlank(sessionToken)) {
            // 세션 토큰이 있으면, 임시 자격 증명(Session Credentials) 사용
            provider = StaticCredentialsProvider.create(
                    AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)
            );
        } else {
            // 세션 토큰이 없으면, 일반적인 ID와 비밀키(Basic Credentials) 사용
            provider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)
            );
        }

        // 2. 클라이언트 상세 설정: 타임아웃과 재시도 전략 설정
        ClientOverrideConfiguration override = ClientOverrideConfiguration.builder()
                // 개별 요청에 대한 타임아웃 (최소 1초 보장)
                .apiCallAttemptTimeout(Duration.ofSeconds(Math.max(1, apiCallAttemptTimeoutSeconds)))
                // 전체 과정에 대한 타임아웃 (최소 1초 보장)
                .apiCallTimeout(Duration.ofSeconds(Math.max(1, apiCallTimeoutSeconds)))
                // 표준 재시도 전략 적용 (네트워크 오류 발생 시 지정된 횟수만큼 재시도)
                .retryStrategy(StandardRetryStrategy.builder()
                        .maxAttempts(Math.max(1, maxRetryAttempts))
                        .build())
                .build();

        // 3. ECR 클라이언트 반환
        return EcrClient.builder()
                .region(region)
                .credentialsProvider(provider)
                .overrideConfiguration(override)
                .build();
    }
}