package dev.rex.demo.docker;

import dev.rex.demo.api.dto.DockerHealthResponse;
import dev.rex.demo.api.dto.DockerImageItem;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DockerCliService {

    /**
     * docker 명령 1회 실행 제한시간
     * <p>
     * - docker pull / docker login은 네트워크 상황에 따라 장시간 대기할 수 있음
     * - 데모 프로젝트는 비동기/큐잉이 없으므로, 요청 스레드가 무한 대기하지 않도록 상한을 둠
     */
    private static final Duration DOCKER_CMD_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Docker 데몬 헬스체크
     * <p>
     * - docker version --format "{{.Server.Version}}" 실행
     * <p>
     * 판단 기준
     * - exitCode==0 && stdout에 Server.Version 존재 -> OK
     * - 그 외 -> Docker daemon not available
     * <p>
     * 목적
     * - 이후 pull/login/images/rmi 같은 모든 기능의 선행 조건 확인
     */
    public DockerHealthResponse healthCheck() {
        try {
            ExecResult r = exec(
                    List.of("docker", "version", "--format", "{{.Server.Version}}"),
                    null,
                    DOCKER_CMD_TIMEOUT
            );

            String version = StringUtils.trimToNull(r.stdout());
            if (r.exitCode() != 0 || version == null) {
                return new DockerHealthResponse(false, "Docker daemon not available: " + safeMsg(r));
            }
            return new DockerHealthResponse(true, "Docker OK (server=" + version + ")");
        } catch (Exception e) {
            return new DockerHealthResponse(false, "Docker health check failed: " + e.getMessage());
        }
    }

    /**
     * docker login --username <u> --password-stdin <registry>
     * <p>
     * - password를 커맨드 인자로 넘기면 프로세스 목록/로그에 노출될 수 있음(보안 리스크)
     * - 그래서 docker 권장 방식인 --password-stdin 사용
     * <p>
     * 주의
     * - registry/username은 필수
     * - password는 stdin으로 전달됨(마스킹 로깅 권장: 상위 서비스에서 처리)
     */
    public void loginWithPasswordStdin(String registry, String username, String password) {
        String reg = StringUtils.trimToNull(registry);
        String user = StringUtils.trimToNull(username);
        if (reg == null || user == null) {
            throw new IllegalArgumentException("registry/username은 필수입니다.");
        }

        try {
            ExecResult r = exec(
                    List.of("docker", "login", "--username", user, "--password-stdin", reg),
                    password,
                    DOCKER_CMD_TIMEOUT
            );
            if (r.exitCode() != 0) {
                throw new IllegalStateException("docker login 실패: " + safeMsg(r));
            }
        } catch (IOException e) {
            throw new IllegalStateException("docker login 실패: " + e.getMessage(), e);
        }
    }

    /**
     * docker pull <imageRef>
     * <p>
     * imageRef 예
     * - <registry>/<repo>:<tag>
     * - 194356...amazonaws.com/demo/rex-repo:v1
     * <p>
     * 실패 케이스 예
     * - repo/tag가 실제로 존재하지 않음 -> not found
     * - 네트워크/인증 문제 -> denied/timeout
     */
    public void pullImage(String imageRef) {
        String ref = StringUtils.trimToNull(imageRef);
        if (ref == null) throw new IllegalArgumentException("imageRef는 필수입니다.");

        try {
            ExecResult r = exec(List.of("docker", "pull", ref), null, DOCKER_CMD_TIMEOUT);
            if (r.exitCode() != 0) {
                throw new IllegalStateException("docker pull 실패: " + safeMsg(r));
            }
        } catch (IOException e) {
            throw new IllegalStateException("docker pull 실패: " + e.getMessage(), e);
        }
    }

    /**
     * docker inspect로 RepoDigest 확인 (있으면 첫 번째 반환)
     * <p>
     * - pull 결과의 신뢰성/식별성 확보
     * - tag는 변할 수 있으나 digest는 immutable(컨텐츠 기반 식별자)라서 결과 확인에 유리
     * <p>
     * 동작
     * - docker inspect --format "{{json .RepoDigests}}" <imageRef>
     * - 예: ["repo@sha256:..."]
     * <p>
     * 주의
     * - digest 조회 실패는 치명적이지 않으므로 null 반환(선택 기능)
     */
    public String inspectDigest(String imageRef) {
        String ref = StringUtils.trimToNull(imageRef);
        if (ref == null) return null;

        try {
            ExecResult r = exec(
                    List.of("docker", "inspect", "--format", "{{json .RepoDigests}}", ref),
                    null,
                    DOCKER_CMD_TIMEOUT
            );
            if (r.exitCode() != 0) {
                log.warn("docker inspect digest 실패. imageRef={}, msg={}", ref, safeMsg(r));
                return null;
            }

            String out = StringUtils.trimToNull(r.stdout());
            if (out == null || "null".equals(out) || "[]".equals(out)) return null;

            // ["repo@sha256:..."] 형태에서 첫 문자열만 추출
            int q1 = out.indexOf('"');
            int q2 = (q1 >= 0) ? out.indexOf('"', q1 + 1) : -1;
            if (q1 >= 0 && q2 > q1) return out.substring(q1 + 1, q2);
            return null;

        } catch (IOException e) {
            log.warn("docker inspect digest 예외. imageRef={}, err={}", ref, e.getMessage());
            return null;
        }
    }

    /**
     * 로컬 이미지 목록 조회
     * <p>
     * docker images --digests --format "{{.Repository}}|{{.Tag}}|{{.Digest}}|{{.ID}}|{{.CreatedSince}}|{{.Size}}"
     * <p>
     * 출력
     * - DockerImageItem 리스트
     * <p>
     * 필터
     * - containsRepo 가 있으면 Repository에 부분 문자열 매칭되는 것만 반환
     * <p>
     * 주의
     * - 로컬 Docker 엔진 기준으로 조회됨
     */
    public List<DockerImageItem> listImages(String containsRepo) {
        String filter = StringUtils.trimToNull(containsRepo);

        try {
            ExecResult r = exec(List.of(
                    "docker", "images", "--digests",
                    "--format", "{{.Repository}}|{{.Tag}}|{{.Digest}}|{{.ID}}|{{.CreatedSince}}|{{.Size}}"
            ), null, DOCKER_CMD_TIMEOUT);

            if (r.exitCode() != 0) {
                throw new IllegalStateException("docker images 실패: " + safeMsg(r));
            }

            // stdout 라인 파싱
            List<DockerImageItem> items = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new StringReader(r.stdout()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String[] parts = line.split("\\|", -1);
                    if (parts.length < 6) continue;

                    String repo = nullToNull(parts[0]);
                    String tag = nullToNull(parts[1]);
                    String digest = nullToNull(parts[2]);
                    String id = nullToNull(parts[3]);
                    String created = nullToNull(parts[4]);
                    String size = nullToNull(parts[5]);

                    // 부분 문자열 필터 적용
                    if (filter != null && (repo == null || !repo.contains(filter))) continue;

                    items.add(new DockerImageItem(repo, tag, digest, id, created, size));
                }
            }
            return items;

        } catch (IOException e) {
            throw new IllegalStateException("docker images 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 로컬 이미지 삭제
     * <p>
     * 입력
     * - imageRef: repo:tag 또는 repo@sha256:... 또는 imageId
     * - force: true면 docker rmi -f
     * <p>
     * 주의
     * - 컨테이너가 이미지 사용 중이면 삭제 실패 가능
     * - -f(force)는 그 경우에도 강제 삭제를 시도
     */
    public void removeImage(String imageRef, boolean force) {
        String ref = StringUtils.trimToNull(imageRef);
        if (ref == null) throw new IllegalArgumentException("imageRef는 필수입니다.");

        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("rmi");
        if (force) cmd.add("-f");
        cmd.add(ref);

        try {
            ExecResult r = exec(cmd, null, DOCKER_CMD_TIMEOUT);
            if (r.exitCode() != 0) {
                throw new IllegalStateException("docker rmi 실패: " + safeMsg(r));
            }
        } catch (IOException e) {
            throw new IllegalStateException("docker rmi 실패: " + e.getMessage(), e);
        }
    }

    /**
     * ProcessBuilder 기반 외부 프로세스 실행 유틸
     * <p>
     * ProcessBuilder 특징
     * - 외부 라이브러리 의존 없이 JDK 기본 기능만으로 구현 가능
     * - 운영 환경(리눅스/윈도우)에서도 동일하게 동작
     * - CLI 동작을 그대로 재현(운영 환경과 동일한 관점)
     * <p>
     * - stdout/stderr를 "동시에" 읽지 않으면 버퍼가 차서 deadlock 가능
     * -> StreamCollector를 각각 스레드로 실행
     * <p>
     * timeout
     * - timeout 초과 시 프로세스 종료/강제 종료 후 예외 발생
     *
     * @param command docker 명령 + 인자 리스트
     * @param stdin   null 아니면 stdin으로 입력(예: password-stdin)
     * @param timeout 실행 제한 시간
     */
    private ExecResult exec(List<String> command, String stdin, Duration timeout) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false); // stdout/stderr 분리 (에러 메시지 우선 처리 가능)

        Process p = pb.start();

        // stdin 처리 (password-stdin 같은 케이스)
        if (stdin != null) {
            // docker login --password-stdin 에서는 stdin에 password가 반드시 들어가야 함
            try (OutputStream os = p.getOutputStream()) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
                // docker는 보통 개행 포함 입력을 기대하므로 끝에 '\n' 보정
                if (!stdin.endsWith("\n")) os.write('\n');
                os.flush();
            }
        } else {
            // stdin을 쓰지 않는 경우라도 반드시 닫아야 프로세스가 stdin 대기 상태에 빠지는 케이스를 회피할 수 있음
            p.getOutputStream().close();
        }

        // stdout/stderr 동시 수집(데드락 방지)
        StreamCollector out = new StreamCollector(p.getInputStream());
        StreamCollector err = new StreamCollector(p.getErrorStream());
        Thread tOut = new Thread(out, "docker-stdout");
        Thread tErr = new Thread(err, "docker-stderr");
        tOut.start();
        tErr.start();

        // timeout 내 종료 대기
        boolean finished;
        try {
            finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            // 인터럽트 상태 복원 + 프로세스 종료 후 예외
            Thread.currentThread().interrupt();
            destroyProcess(p);
            throw new IOException("command interrupted: " + String.join(" ", command), ie);
        }

        if (!finished) {
            // timeout 초과 시 강제 종료
            destroyProcess(p);
            throw new IOException("command timeout(" + timeout.toSeconds() + "s): " + String.join(" ", command));
        }

        int exit = p.exitValue();

        // 수집 스레드 종료 대기(짧게)
        // - 프로세스 종료 직후에도 스트림 flush가 남아있을 수 있어 join
        try {
            tOut.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            tErr.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        return new ExecResult(exit, out.text(), err.text());
    }

    /**
     * 프로세스 종료 유틸
     * - 정상 destroy 시도 후, 일정 시간 내 종료 안되면 destroyForcibly
     * <p>
     * - timeout/interrupt 발생 시 좀비 프로세스 방지
     */
    private void destroyProcess(Process p) {
        try {
            p.destroy();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
            p.destroyForcibly();
        }
    }

    /**
     * 에러 메시지 추출
     * - stderr 우선, 없으면 stdout
     */
    private String safeMsg(ExecResult r) {
        String s = StringUtils.trimToNull(r.stderr());
        if (s == null) s = StringUtils.trimToNull(r.stdout());
        return (s == null) ? "(no output)" : s;
    }

    /**
     * docker 출력 파싱용 유틸
     * - null/blank/<none> 은 null 처리
     */
    private String nullToNull(String s) {
        String t = StringUtils.trimToNull(s);
        if (t == null) return null;
        if ("<none>".equalsIgnoreCase(t)) return null;
        return t;
    }

    /**
     * exec 결과 값 객체
     * - exitCode : 프로세스 종료 코드
     * - stdout   : 표준 출력
     * - stderr   : 표준 에러
     */
    private record ExecResult(int exitCode, String stdout, String stderr) {
    }

    /**
     * stdout/stderr를 별도 스레드에서 끝까지 읽어 buffer에 저장하는 수집기
     * <p>
     * - 프로세스가 stdout/stderr에 많이 쓰면 OS 버퍼가 찰 수 있음
     * - 버퍼가 차면 프로세스가 write에서 block되고, waitFor도 끝나지 않는 deadlock이 발생 가능
     * - 따라서 stdout과 stderr를 동시에 읽어야 안전
     * - 프로세스 강제 종료 시 IOException이 발생할 수 있으나 무시
     */
    private static class StreamCollector implements Runnable {
        private final InputStream is;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private StreamCollector(InputStream is) {
            this.is = is;
        }

        @Override
        public void run() {
            try (InputStream in = is) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) >= 0) {
                    if (n > 0) buffer.write(b, 0, n);
                }
            } catch (IOException ignored) {
                // 프로세스 종료/강제 종료 시 흔히 발생 가능 -> 데모에선 무시
            }
        }

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
