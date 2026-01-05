package dev.rex.demo.infra.aws;

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
import java.util.Objects;

/**
 * AWS ECR 클라이언트(EcrClient) 생성 팩토리
 * <p>
 * 책임:
 * - 입력(region/credentials)을 AWS SDK 타입으로 변환
 * - CredentialsProvider 구성(세션 토큰 유무)
 * - ClientOverrideConfiguration(타임아웃/재시도) 구성
 *
 * <p>
 * 설계 의도(팀 공유용):
 * - create()는 “오케스트레이션”만 담당하고, 세부는 private 메서드로 분리
 * - 정책(최소값 보정)을 한 곳에서 관리하여 설정값이 비정상이어도 안전하게 동작
 * - 주석은 “왜/무엇을” 설명하고, 코드가 말하는 “어떻게”는 과도하게 반복하지 않음
 */
@Component
public class EcrClientFactory {

    // 설정 키(문자열) 자체는 스프링이 관리하지만, 최소값 정책은 코드에서 보정

    /**
     * 1회 API 호출 시도에 대한 타임아웃(초). 네트워크 지연/서버 지연 방어
     */
    @Value("${demo.aws.ecr.api-call-attempt-timeout-seconds:10}")
    private long apiCallAttemptTimeoutSeconds;

    /**
     * 전체 API 호출(재시도 포함) 총 타임아웃(초). 무한 재시도/장시간 블로킹 방어
     */
    @Value("${demo.aws.ecr.api-call-timeout-seconds:15}")
    private long apiCallTimeoutSeconds;

    /**
     * 재시도 포함 최대 시도 횟수 (AWS SDK의 maxAttempts는 1 이상이어야 함)
     */
    @Value("${demo.aws.ecr.max-retry-attempts:3}")
    private int maxRetryAttempts;

    /**
     * 제공된 인증 정보로 ECR 클라이언트를 생성
     *
     * <p>중요:
     * - regionStr/accessKeyId/secretAccessKey는 필수
     * - sessionToken이 있으면 임시 자격 증명(AwsSessionCredentials), 없으면 기본 자격 증명(AwsBasicCredentials)
     * - timeout/retry 설정은 “최소 1” 정책으로 보정한다(잘못된 설정값 방어)
     *
     * @param regionStr       접속할 AWS region (예: "ap-northeast-2")
     * @param accessKeyId     AWS Access Key Id
     * @param secretAccessKey AWS Secret Access Key
     * @param sessionToken    세션 토큰(선택). STS 임시 자격 증명일 때 사용
     * @return 구성 완료된 {@link EcrClient}
     */
    public EcrClient create(String regionStr, String accessKeyId, String secretAccessKey, String sessionToken) {
        // 0) 필수 입력 방어: NPE/알 수 없는 region으로 인한 SDK 예외를 “초기에” 명확히 발생시킴
        String region = requireText(regionStr, "regionStr");
        String ak = requireText(accessKeyId, "accessKeyId");
        String sk = requireText(secretAccessKey, "secretAccessKey");

        // 1) Region 변환: 문자열 -> AWS SDK Region
        Region awsRegion = Region.of(region);

        // 2) CredentialsProvider 구성: sessionToken 유무에 따라 Basic vs Session 결정
        AwsCredentialsProvider credentialsProvider = buildCredentialsProvider(ak, sk, sessionToken);

        // 3) 클라이언트 공통 정책(타임아웃/재시도) 구성
        ClientOverrideConfiguration override = buildOverrideConfiguration();

        // 4) 최종 EcrClient 생성(불변 객체) 후 반환
        return EcrClient.builder()
                .region(awsRegion)
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(override)
                .build();
    }

    /**
     * CredentialsProvider 생성
     * <p>
     * - sessionToken이 존재하면 AwsSessionCredentials 사용
     * - 없으면 AwsBasicCredentials 사용
     *
     * <p>왜 분리?
     * - 인증 방식 선택 로직이 create() 흐름을 흐리지 않도록 캡슐화
     * - 추후 AssumeRole/프로파일/환경변수 방식으로 확장할 때 변경 범위를 최소화
     */
    private AwsCredentialsProvider buildCredentialsProvider(String accessKeyId, String secretAccessKey, String sessionToken) {
        // 세션 토큰이 있는 경우: 임시 자격 증명(3요소)로 구성
        if (StringUtils.isNotBlank(sessionToken)) {
            return StaticCredentialsProvider.create(
                    AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken.trim())
            );
        }

        // 세션 토큰이 없는 경우: 기본 자격 증명(2요소)로 구성
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey)
        );
    }

    /**
     * ClientOverrideConfiguration 생성
     * <p>
     * 포함 정책:
     * - apiCallAttemptTimeout: 단일 시도 타임아웃(초) (최소 1초)
     * - apiCallTimeout: 전체 호출 타임아웃(초) (최소 1초)
     * - retryStrategy: StandardRetryStrategy (maxAttempts 최소 1)
     *
     */
    private ClientOverrideConfiguration buildOverrideConfiguration() {
        long attemptSeconds = atLeastOne(apiCallAttemptTimeoutSeconds);
        long totalSeconds = atLeastOne(apiCallTimeoutSeconds);
        int attempts = atLeastOne(maxRetryAttempts);

        return ClientOverrideConfiguration.builder()
                // 개별 시도 타임아웃: 지나치게 긴 hang 방지
                .apiCallAttemptTimeout(Duration.ofSeconds(attemptSeconds))
                // 전체 타임아웃: 재시도 포함 총 시간 상한
                .apiCallTimeout(Duration.ofSeconds(totalSeconds))
                // 표준 재시도 전략: 네트워크/일시적 오류에 대해 제한된 횟수만 재시도
                .retryStrategy(StandardRetryStrategy.builder()
                        .maxAttempts(attempts)
                        .build())
                .build();
    }

    /**
     * 필수 문자열 입력 검증(공백/빈 문자열 불허)
     * - IllegalArgumentException으로 빠르게 실패하여 원인을 명확히 함
     */
    private String requireText(String v, String name) {
        Objects.requireNonNull(name, "name");
        String t = StringUtils.trimToNull(v);
        if (t == null) throw new IllegalArgumentException(name + " is blank");
        return t;
    }

    /**
     * 최소 1 보정(초/횟수 공통)
     */
    private long atLeastOne(long v) {
        return Math.max(1L, v);
    }

    /**
     * 최소 1 보정(초/횟수 공통)
     */
    private int atLeastOne(int v) {
        return Math.max(1, v);
    }
}
