package dev.rex.demo.service;

import dev.rex.demo.api.dto.EcrPullRequest;
import dev.rex.demo.api.dto.EcrPullResponse;
import dev.rex.demo.docker.DockerCliService;
import dev.rex.demo.ecr.EcrAuthService;
import dev.rex.demo.ecr.model.EcrLoginToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * AWS ECR 이미지 Pull 서비스
 * <p>
 * - 입력받은 AWS 인증 정보로 ECR 로그인 토큰 발급
 * - Docker CLI를 이용해 실제 docker login / docker pull 수행
 * - pull 결과에 대한 최소한의 메타정보 반환
 * <p>
 * - AWS ECR API 호출 책임: EcrAuthService
 * - Docker CLI 실행 책임   : DockerCliService
 * - 이 클래스는 "흐름 제어(Orchestration)"만 담당
 * <p>
 * - secretAccessKey는 절대 로그에 남기지 않음
 * - accessKeyId도 부분 마스킹 후 로그 출력
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcrPullService {

    /**
     * tag 미지정 시 기본값
     * - docker / ECR 관례상 latest
     */
    private static final String TAG_LATEST = "latest";

    /**
     * ECR 인증 토큰 발급 서비스
     * - GetAuthorizationToken API 호출
     */
    private final EcrAuthService ecrAuthService;

    /**
     * Docker CLI 실행 서비스
     * - docker login
     * - docker pull
     * - docker inspect
     */
    private final DockerCliService dockerCliService;

    /**
     * accessKeyId 부분 마스킹
     * <p>
     * - 입력 : AKIA123456789XYZ
     * - 출력 : AKIA****YZ
     * <p>
     * - 식별 가능성은 유지
     * - 전체 노출은 금지
     */
    private static String maskAccessKeyId(String s) {
        if (StringUtils.isBlank(s)) return s;

        String t = s.trim();

        if (t.length() <= 6) return "******";

        return t.substring(0, 4)
                + "****"
                + t.substring(t.length() - 2);
    }

    /**
     * ECR 이미지 Pull 메인 로직
     * <p>
     * 처리 흐름
     * 1) 요청 로그 출력 (민감 정보 마스킹)
     * 2) tag 정규화 (null/blank → latest)
     * 3) ECR 로그인 토큰 발급
     * 4) docker login 실행
     * 5) imageRef 구성
     * 6) docker pull 실행
     * 7) docker inspect로 digest 조회(선택)
     * <p>
     * - 이 메서드는 실제 로컬 Docker 엔진의 상태를 변경
     * - pull 결과 이미지는 "서버 머신의 Docker 저장소"에 저장됨
     */
    public EcrPullResponse pull(EcrPullRequest req) {
        // - secretAccessKey는 절대 로그에 남기지 않음
        // - accessKeyId는 식별 가능하도록 부분 마스킹
        log.info(
                "ECR pull requested. region={}, accountId={}, accessKeyId={}, repositoryName={}, tag={}",
                req.region(),
                req.accountId(),
                maskAccessKeyId(req.accessKeyId()),
                req.repositoryName(),
                StringUtils.defaultIfBlank(req.tag(), TAG_LATEST)
        );

        // tag 정규화: null / blank / 공백만 있는 경우 latest로 처리
        String tag = StringUtils.defaultIfBlank(
                StringUtils.trimToNull(req.tag()),
                TAG_LATEST
        );

        // 1) ECR 로그인 토큰 발급
        // 결과:
        // - registry  : xxxx.dkr.ecr.region.amazonaws.com
        // - username  : 보통 "AWS"
        // - password  : 임시 비밀번호(authorization token)
        EcrLoginToken token = ecrAuthService.getLoginToken(
                req.region(),
                req.accountId(),
                req.accessKeyId(),
                req.secretAccessKey()
        );

        // 2) docker login
        // - password-stdin 방식 사용
        // - 커맨드라인 인자에 비밀번호 노출 방지
        dockerCliService.loginWithPasswordStdin(
                token.registry(),
                token.username(),
                token.password()
        );

        // 3) imageRef 구성
        // 형식: <registry>/<repositoryName>:<tag>
        // 예: 194356581254.dkr.ecr.ap-northeast-2.amazonaws.com/demo/rex-repo:v1
        String imageRef = token.registry()
                + "/"
                + req.repositoryName()
                + ":"
                + tag;

        // 4) docker pull 실행
        // - 실제 네트워크 통신 발생
        // - 이미지 레이어가 로컬 Docker 엔진에 저장됨
        dockerCliService.pullImage(imageRef);

        // 5) docker inspect로 digest 확인 (선택)
        // - 실패해도 pull 자체는 성공으로 간주
        String digest = dockerCliService.inspectDigest(imageRef);

        // API 응답 구성
        return new EcrPullResponse(
                imageRef,
                tag,
                digest,
                "SUCCESS"
        );
    }
}
