package dev.rex.demo.ecr;

import dev.rex.demo.ecr.model.EcrLoginToken;
import lombok.RequiredArgsConstructor;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenRequest;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenResponse;

import java.nio.charset.StandardCharsets;

/**
 * AWS ECR 인증 토큰 발급 서비스
 * <p>
 * - AWS ECR GetAuthorizationToken API 호출
 * - docker login 에 필요한 인증 정보 추출
 * <p>
 * - AWS ECR은 IAM accessKey/secretKey로 직접 docker login을 하지 않음
 * - 반드시 ECR API를 통해 "임시 Authorization Token"을 발급받아야 함
 * <p>
 * 결과
 * - registry      : docker login 대상 레지스트리 주소
 * - username      : 일반적으로 "AWS"
 * - password      : 임시 비밀번호(토큰)
 * <p>
 * 이 서비스의 출력(EcrLoginToken)은 DockerCliService.loginWithPasswordStdin()의 입력으로 사용
 */
@Service
@RequiredArgsConstructor
public class EcrAuthService {

    /**
     * ECR Client 생성 책임 분리
     * - region, accessKeyId, secretAccessKey 설정
     * - timeout/retry 등 공통 설정은 Factory에서 관리
     */
    private final AwsEcrClientFactory ecrClientFactory;

    /**
     * docker login 에 사용할 ECR 인증 토큰 발급
     * <p>
     * 입력
     * - region          : AWS region (예: ap-northeast-2)
     * - accountId       : AWS Account ID (registryId)
     * - accessKeyId     : IAM Access Key
     * - secretAccessKey : IAM Secret Key
     * <p>
     * 처리 흐름
     * 1) ECR Client 생성
     * 2) GetAuthorizationToken API 호출
     * 3) authorizationData 에서 proxyEndpoint / authorizationToken 추출
     * 4) Base64 decode → "username:password" 파싱
     * 5) docker login 에 필요한 registry / username / password 반환
     * <p>
     * 주의 사항
     * - authorizationToken은 "임시 토큰"이며 유효기간 존재
     * - 매 pull 시 재발급하는 방식이 가장 단순하고 안전(캐싱 생략)
     *
     * @return EcrLoginToken (registry, username, password)
     */
    public EcrLoginToken getLoginToken(
            String region,
            String accountId,
            String accessKeyId,
            String secretAccessKey
    ) {
        // try-with-resources: EcrClient는 close 필요
        try (EcrClient ecr = ecrClientFactory.create(region, accessKeyId, secretAccessKey)) {
            // registryId(accountId) 기준으로 ECR 인증 토큰 요청
            GetAuthorizationTokenRequest req = GetAuthorizationTokenRequest.builder()
                    .registryIds(accountId)
                    .build();

            GetAuthorizationTokenResponse resp = ecr.getAuthorizationToken(req);

            // authorizationData는 최소 1개 이상이어야 정상
            if (resp.authorizationData() == null || resp.authorizationData().isEmpty()) {
                throw new IllegalStateException("ECR authorizationData가 비어있습니다.");
            }

            // 일반적으로 첫 번째 항목을 사용
            AuthorizationData data = resp.authorizationData().get(0);

            // 예: https://123456789012.dkr.ecr.ap-northeast-2.amazonaws.com
            String proxyEndpoint = StringUtils.trimToNull(data.proxyEndpoint());

            // Base64 인코딩된 "username:password"
            String tokenB64 = StringUtils.trimToNull(data.authorizationToken());

            if (proxyEndpoint == null || tokenB64 == null) {
                throw new IllegalStateException("ECR proxyEndpoint/authorizationToken이 비어있습니다.");
            }

            // docker login 대상 registry
            // proxyEndpoint에는 scheme(https://)이 포함되어 있으므로 제거
            // proxyEndpoint = https://xxxx.dkr.ecr.region.amazonaws.com
            // registry      = xxxx.dkr.ecr.region.amazonaws.com
            String registry = proxyEndpoint
                    .replace("https://", "")
                    .replace("http://", "");

            // authorizationToken decode
            // Base64 decode 결과 예:
            // "AWS:abcdefghijklmnopqrstuvwxyz"
            // 앞부분(AWS)은 username, 뒷부분이 docker login 에서 사용할 password
            String decoded = new String(Base64.decodeBase64(tokenB64), StandardCharsets.UTF_8);

            int idx = decoded.indexOf(':');

            if (idx <= 0 || idx == decoded.length() - 1) {
                throw new IllegalStateException("authorizationToken decode 형식이 예상과 다릅니다.");
            }

            String username = decoded.substring(0, idx);
            String password = decoded.substring(idx + 1);

            // DockerCliService.loginWithPasswordStdin()에 그대로 전달
            return new EcrLoginToken(registry, username, password);
        }
    }
}
