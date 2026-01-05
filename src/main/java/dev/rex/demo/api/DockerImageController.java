package dev.rex.demo.api;

import dev.rex.demo.api.dto.DockerImagesResponse;
import dev.rex.demo.api.dto.DockerRemoveImageRequest;
import dev.rex.demo.api.dto.DockerRemoveImageResponse;
import dev.rex.demo.docker.DockerCliService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * 로컬 Docker 이미지 조회 / 삭제 API
 * <p>
 * - 로컬 Docker 엔진에 존재하는 이미지 목록 조회
 * - 특정 이미지 삭제 요청 처리
 * <p>
 * - Docker CLI 호출 책임은 DockerCliService에 위임
 * - Controller는 HTTP 요청/응답 매핑만 담당
 * <p>
 * 주의
 * - 이 API는 "로컬 Docker 엔진"을 직접 조작
 * - 서버가 실행 중인 머신의 Docker 이미지가 변경됨
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/api/docker", produces = MediaType.APPLICATION_JSON_VALUE)
public class DockerImageController {

    /**
     * Docker CLI 호출 서비스
     * - docker images
     * - docker rmi
     */
    private final DockerCliService dockerCliService;

    /**
     * 로컬에 내려받은 Docker 이미지 목록 조회
     * <p>
     * 엔드포인트
     * GET /api/docker/images
     * <p>
     * 쿼리 파라미터
     * - containsRepo (선택)
     * * repository 문자열 부분 매칭 필터
     * * 예: demo/rex-repo
     * <p>
     * 동작
     * - docker images --digests 실행
     * - DockerCliService.listImages(...) 호출
     * <p>
     * 반환
     * - 이미지 개수(count)
     * - 이미지 상세 목록(items)
     * <p>
     * 주의
     * - 로컬 Docker 엔진 기준 결과
     * - ECR과는 직접적인 연관 없음
     */
    @GetMapping("/images")
    public DockerImagesResponse listImages(
            @RequestParam(value = "containsRepo", required = false) String containsRepo
    ) {
        var items = dockerCliService.listImages(containsRepo);
        return new DockerImagesResponse(items.size(), items);
    }

    /**
     * 로컬 Docker 이미지 삭제
     * <p>
     * 엔드포인트
     * DELETE /api/docker/images
     * <p>
     * 요청 바디
     * - imageRef (필수)
     * * repo:tag
     * * repo@sha256:...
     * * imageId
     * - force (선택)
     * * true  -> docker rmi -f
     * * false -> docker rmi
     * <p>
     * 동작
     * - DockerCliService.removeImage(...) 호출
     * <p>
     * 주의
     * - 컨테이너가 이미지 사용 중이면 삭제 실패 가능
     * - force=true는 해당 제한을 무시하고 강제 삭제 시도
     */
    @DeleteMapping(value = "/images", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DockerRemoveImageResponse removeImage(@Valid @RequestBody DockerRemoveImageRequest req) {
        dockerCliService.removeImage(req.imageRef(), req.force());
        return new DockerRemoveImageResponse(req.imageRef(), true, "REMOVED");
    }
}
