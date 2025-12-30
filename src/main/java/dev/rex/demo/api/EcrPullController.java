package dev.rex.demo.api;

import dev.rex.demo.api.dto.*;
import dev.rex.demo.docker.DockerCliService;
import dev.rex.demo.service.EcrPullService;
import dev.rex.demo.service.EcrRepoScanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * AWS ECR Pull Demo API Controller
 * <p>
 * - Docker 데몬 상태 확인(health check)
 * - AWS ECR Repository 목록 조회 + 최신 tag 계산
 * - 선택된 Repository/Tag에 대해 실제 docker pull 수행
 * <p>
 * 특징
 * - DB, 화면(UI), 인증 없음
 * - 모든 동작은 "실제 Docker CLI"를 통해 실행됨
 * - 이미지 결과는 애플리케이션 내부가 아닌 "로컬 Docker 엔진"에 저장됨
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class EcrPullController {

    /**
     * ECR 이미지 pull 전용 서비스
     * - ECR 인증 토큰 발급
     * - docker login
     * - docker pull
     * - digest 조회
     */
    private final EcrPullService ecrPullService;

    /**
     * ECR Repository 스캔 서비스
     * - 모든 Repository 조회
     * - 각 Repository의 최신 tag 계산
     * - 이미지가 없는 Repository는 pull 불가로 표시
     */
    private final EcrRepoScanService ecrRepoScanService;

    /**
     * Docker CLI 연동 서비스
     * - docker version
     * - docker images
     * - docker rmi
     * - docker pull
     * <p>
     * ※ Docker Java SDK가 아닌 실제 CLI 호출 방식
     */
    private final DockerCliService dockerCliService;

    /**
     * (필수) Docker 헬스체크
     * <p>
     * - 이 서버에서 Docker 데몬이 실제로 동작하는지 확인
     * - 이후 모든 ECR pull 작업의 선행 조건
     * - docker version 명령 실행
     *
     * @return DockerHealthResponse
     */
    @GetMapping("/health/docker")
    public DockerHealthResponse dockerHealth() {
        return dockerCliService.healthCheck();
    }

    /**
     * (1단계) AWS ECR 전체 Repository + 최신 tag 조회
     * <p>
     * 입력값
     * - region
     * - accountId
     * - accessKeyId
     * - secretAccessKey
     * <p>
     * 처리 내용
     * - ECR DescribeRepositories 호출
     * - 각 Repository에 대해 DescribeImages로 최신 tag 계산
     * - 이미지가 없는 Repository는 pullable=false 로 표시
     * - "존재하지 않는 tag로 docker pull" 하는 오류를 사전에 차단
     *
     * @param req EcrRepoScanRequest
     * @return EcrRepoScanResponse
     */
    @PostMapping(value = "/ecr/repositories", consumes = MediaType.APPLICATION_JSON_VALUE)
    public EcrRepoScanResponse listReposWithLatestTag(
            @Valid @RequestBody EcrRepoScanRequest req
    ) {
        return ecrRepoScanService.scanAllReposWithLatestTag(req);
    }

    /**
     * (2단계) AWS ECR 이미지 Pull 실행
     * <p>
     * 입력값
     * - region
     * - accountId
     * - accessKeyId
     * - secretAccessKey
     * - repositoryName
     * - tag
     * <p>
     * 처리 흐름
     * 1) ECR 인증 토큰 발급
     * 2) docker login (password-stdin)
     * 3) docker pull <registry>/<repo>:<tag>
     * 4) docker inspect로 digest 확인
     * <p>
     * 주의사항
     * - 이미지는 Spring Boot 애플리케이션 내부에 저장되지 않음
     * - 이 서버의 "로컬 Docker 엔진"에 저장됨
     *
     * @param req EcrPullRequest
     * @return EcrPullResponse
     */
    @PostMapping(value = "/ecr/pull", consumes = MediaType.APPLICATION_JSON_VALUE)
    public EcrPullResponse pull(
            @Valid @RequestBody EcrPullRequest req
    ) {
        return ecrPullService.pull(req);
    }
}
