package dev.rex.demo.infra.fs;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.common.util.Masking;
import dev.rex.demo.common.util.Retry;
import dev.rex.demo.infra.aws.EcrErrorMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.EcrException;
import software.amazon.awssdk.services.ecr.model.GetDownloadUrlForLayerRequest;
import software.amazon.awssdk.services.ecr.model.GetDownloadUrlForLayerResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * ECR Layer Blob 다운로드 컴포넌트
 * <p>
 * 책임:
 * - ECR GetDownloadUrlForLayer로 presigned URL 획득
 * - HTTP GET 스트리밍으로 파일 저장(.part -> commit)
 * - (옵션) SHA-256 무결성 검증
 * - (옵션) 재시도(429/5xx/timeout/일부 I/O)
 *
 * <p>
 * 설계/정책:
 * - 다운로드는 "임시 파일(.part)"에 먼저 저장하고, 검증 성공 후 target으로 커밋(move)
 * - HTTP 401/403이 나오면 Basic Auth 헤더를 붙여 1회 재시도(토큰이 있을 때만).
 * - 재시도는 최대 retries회. (호출자가 0을 줘도 최소 1회 보장)
 * - 파일 시스템 오류는 가능한 범위에서 ErrorCode로 분류
 */
@Slf4j
@Component
public class BlobDownloader {

    // =========================
    // HTTP Client (shared)
    // =========================
    /**
     * Java HttpClient는 thread-safe로 공유 가능.
     * - follow redirects: presigned URL이 redirect 될 수 있어 NORMAL 적용
     * - connect timeout: connect 자체 제한
     */
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // =========================
    // Backoff config
    // =========================
    @Value("${app.download.backoffBaseMillis:300}")
    private long backoffBaseMillis;

    @Value("${app.download.backoffMaxMillis:5000}")
    private long backoffMaxMillis;

    // =========================
    // Public API
    // =========================

    /**
     * 단일 레이어 Blob 다운로드
     * <p>
     * 흐름:
     * 1) 입력 정규화/검증
     * 2) ECR에서 presigned download URL 획득
     * 3) HTTP GET으로 스트리밍 다운로드(.part)
     * 4) (옵션) SHA-256 검증
     * 5) 원자적 move(가능하면 ATOMIC_MOVE)로 최종 경로로 커밋
     * 6) 성공 시 targetPath 반환
     *
     * <p>
     * 재시도 정책(기본):
     * - HTTP 429 or 5xx: 재시도 대상
     * - HttpTimeoutException: 재시도 대상
     * - IOException(전송 I/O로 추정): 재시도 대상 (최종 시 DOWNLOAD_HTTP_CONNECTION_FAILED)
     *
     * @return 최종 저장 경로(targetPath)
     */
    public Path downloadLayer(
            EcrClient ecr,
            String registryId,
            String repositoryName,
            String layerDigest,
            Path targetPath,
            String basicAuthToken,
            int httpTimeoutSeconds,
            int maxRetries,
            boolean verifySha256
    ) {
        // 0) 필수 입력 방어: NPE/의미없는 API 호출 방지
        Objects.requireNonNull(ecr, "ecr");
        Objects.requireNonNull(targetPath, "targetPath");

        String repo = requireText(repositoryName, "repositoryName");
        String digest = requireText(layerDigest, "layerDigest");

        // 1) 재시도/타임아웃 정규화
        //    - maxRetries: 0이 들어와도 최소 1회는 시도
        //    - timeout: 0/음수면 기본 180초
        int retries = Math.max(1, maxRetries);
        int timeoutSec = (httpTimeoutSeconds <= 0) ? 180 : httpTimeoutSeconds;

        // 2) ECR presigned URL 획득 (여기서 ECR 권한/존재 여부가 먼저 검증된다)
        String downloadUrl = getDownloadUrl(ecr, registryId, repo, digest);

        // 3) 저장 준비: tmp(.part)로 받고, 성공 시 commit(move)
        Path tmp = tmpPath(targetPath);
        DownloadLayout.mkdirs(targetPath.getParent());

        // 4) 재시도 루프: 성공하면 즉시 return
        for (int attempt = 1; attempt <= retries; attempt++) {
            HttpResponse<InputStream> resp = null;

            try {
                // 4-1) 1차 요청: Authorization 없이 시도(대부분 presigned URL은 헤더 없어도 됨)
                resp = sendGet(downloadUrl, timeoutSec, false, basicAuthToken);
                int code = resp.statusCode();

                // 4-2) 401/403이면 Basic Auth 헤더로 1회 더 시도(토큰이 있을 때만)
                if (isUnauthorized(code) && StringUtils.isNotBlank(basicAuthToken)) {
                    closeQuiet(resp);
                    resp = sendGet(downloadUrl, timeoutSec, true, basicAuthToken);
                    code = resp.statusCode();
                }

                // 4-3) 재시도 가능한 상태코드(429/5xx)라면 Retryable로 변환하여 공통 처리
                if (isRetryableStatus(code)) {
                    closeQuiet(resp);
                    throw new RetryableHttpException(code, "retryable http status=" + code);
                }

                // 4-4) 그 외 비정상 상태코드는 즉시 실패(재시도 대상 아님)
                //      - 401/403: 인증/권한
                //      - 404: blob not found
                //      - 나머지: unexpected status
                if (!is2xx(code)) {
                    URI uri = URI.create(downloadUrl);
                    closeQuiet(resp);

                    ErrorCode ec = mapHttpFailure(code);
                    throw new ApiException(
                            ec,
                            "HTTP 다운로드 실패 status=" + code +
                                    ", host=" + safeHost(uri) +
                                    ", digest=" + Masking.maskDigest(digest),
                            Map.of(
                                    "status", code,
                                    "host", safeHost(uri),
                                    "digest", Masking.maskDigest(digest)
                            )
                    );
                }

                // 4-5) 본문 스트리밍 저장 + (옵션) sha 계산
                //      - verifySha256=true일 때만 sha256을 계산/반환
                String computedHex;
                try (InputStream body = resp.body()) {
                    computedHex = streamToFileAndDigest(body, tmp, verifySha256);
                }

                // 4-6) (옵션) sha256 검증
                if (verifySha256) {
                    verifySha256OrThrow(digest, computedHex, tmp);
                }

                // 4-7) 임시파일 커밋: 최종 파일로 move
                commitTmp(tmp, targetPath);

                log.info("Downloaded OK. digest={}, path={}",
                        Masking.maskDigest(digest),
                        targetPath.toAbsolutePath());
                return targetPath;

            } catch (RetryableHttpException re) {
                // 5) 429/5xx: 백오프 후 재시도
                safeDelete(tmp);

                ErrorCode finalCode = (re.status == 429)
                        ? ErrorCode.DOWNLOAD_ECR_THROTTLED
                        : ErrorCode.DOWNLOAD_HTTP_UNEXPECTED_STATUS;

                if (attempt >= retries) {
                    // 최종 실패: 재시도 초과
                    throw new ApiException(
                            finalCode,
                            "HTTP 재시도 초과: status=" + re.status + ", digest=" + Masking.maskDigest(digest),
                            Map.of("status", re.status, "digest", Masking.maskDigest(digest)),
                            re
                    );
                }

                // 재시도 진행
                backoffAndSleep(attempt, retries, digest, re.getMessage());

            } catch (HttpTimeoutException te) {
                // 6) HTTP timeout: 백오프 후 재시도
                safeDelete(tmp);

                if (attempt >= retries) {
                    throw new ApiException(
                            ErrorCode.DOWNLOAD_HTTP_TIMEOUT,
                            "HTTP timeout 재시도 초과: " + te.getMessage() + ", digest=" + Masking.maskDigest(digest),
                            Map.of("digest", Masking.maskDigest(digest)),
                            te
                    );
                }

                backoffAndSleep(attempt, retries, digest, te.getMessage());

            } catch (InterruptedException ie) {
                // 7) 인터럽트는 즉시 중단 + interrupt flag 복구
                safeDelete(tmp);
                Thread.currentThread().interrupt();

                throw new ApiException(
                        ErrorCode.DOWNLOAD_THREAD_INTERRUPTED,
                        "다운로드 중단(Interrupted). digest=" + Masking.maskDigest(digest),
                        Map.of("digest", Masking.maskDigest(digest)),
                        ie
                );

            } catch (AccessDeniedException ade) {
                // 8) 파일 권한 문제: 재시도해도 해결될 가능성이 낮으므로 즉시 실패
                safeDelete(tmp);

                throw new ApiException(
                        ErrorCode.DOWNLOAD_FS_PERMISSION_DENIED,
                        "파일 권한 오류: " + ade.getMessage() + ", digest=" + Masking.maskDigest(digest),
                        Map.of("digest", Masking.maskDigest(digest), "path", String.valueOf(targetPath)),
                        ade
                );

            } catch (FileSystemException fse) {
                // 9) 파일 시스템 오류: 메시지 기반으로 가능한 범위에서 ErrorCode 분류
                safeDelete(tmp);

                ErrorCode fsCode = classifyFileSystemException(fse);
                throw new ApiException(
                        fsCode,
                        "파일 시스템 오류: " + safeFsMsg(fse) + ", digest=" + Masking.maskDigest(digest),
                        Map.of("digest", Masking.maskDigest(digest), "path", String.valueOf(targetPath)),
                        fse
                );

            } catch (IOException ioe) {
                // 10) I/O 오류(대부분 HTTP send/stream 중 네트워크 오류로 간주)
                //     - 마지막 시도면 실패
                //     - 아니면 백오프 후 재시도
                safeDelete(tmp);

                if (attempt >= retries) {
                    throw new ApiException(
                            ErrorCode.DOWNLOAD_HTTP_CONNECTION_FAILED,
                            "다운로드 I/O 재시도 초과: " + ioe.getMessage() + ", digest=" + Masking.maskDigest(digest),
                            Map.of("digest", Masking.maskDigest(digest)),
                            ioe
                    );
                }

                backoffAndSleep(attempt, retries, digest, ioe.getMessage());

            } catch (ApiException ae) {
                // 11) 이미 정책 코드가 지정된 예외는 그대로 전파(단, tmp 정리)
                safeDelete(tmp);
                throw ae;

            } catch (RuntimeException ex) {
                // 12) 그 외 런타임 예외는 일반 실패로 래핑
                safeDelete(tmp);

                throw new ApiException(
                        ErrorCode.DOWNLOAD_TASK_FAILED,
                        "다운로드 실패: " + ex.getMessage() + ", digest=" + Masking.maskDigest(digest),
                        Map.of("digest", Masking.maskDigest(digest)),
                        ex
                );

            } finally {
                // 13) resp body 스트림 close 보장(try-with-resources는 body에만 적용했기 때문에 여기서도 안전 정리)
                closeQuiet(resp);
            }
        }

        // 논리상 도달하면 안 되지만, 방어적으로 처리
        throw new ApiException(
                ErrorCode.DOWNLOAD_TASK_FAILED,
                "Unexpected downloader exit. digest=" + Masking.maskDigest(digest),
                Map.of("digest", Masking.maskDigest(digest))
        );
    }

    // =========================
    // ECR: presigned URL
    // =========================

    /**
     * GetDownloadUrlForLayer를 호출하여 presigned download URL을 얻음
     * <p>
     * 실패 시:
     * - EcrException: status/awsCode 기반 분류 후 ApiException
     * - SdkClientException: 네트워크/클라이언트 오류로 ApiException
     */
    private String getDownloadUrl(EcrClient ecr, String registryId, String repo, String digest) {
        try {
            // 1) ECR API 호출
            GetDownloadUrlForLayerResponse u = ecr.getDownloadUrlForLayer(
                    GetDownloadUrlForLayerRequest.builder()
                            .registryId(registryId)
                            .repositoryName(repo)
                            .layerDigest(digest)
                            .build()
            );

            // 2) URL 검증: null/blank면 호출 의미가 없으므로 실패 처리
            String url = StringUtils.trimToNull(u.downloadUrl());
            if (url == null) {
                throw new ApiException(
                        ErrorCode.DOWNLOAD_ECR_API_FAILED,
                        "GetDownloadUrlForLayer 결과 downloadUrl이 비었습니다. digest=" + Masking.maskDigest(digest),
                        Map.of("digest", Masking.maskDigest(digest))
                );
            }

            return url;

        } catch (EcrException ee) {
            // 3) AWS 예외를 서비스 정책 코드로 매핑
            ErrorCode ec = classifyEcrException(ee);

            throw new ApiException(
                    ec,
                    "ECR GetDownloadUrlForLayer 실패: " + safeAwsMsg(ee),
                    Map.of(
                            "status", ee.statusCode(),
                            "digest", Masking.maskDigest(digest),
                            "repo", repo
                    ),
                    ee
            );

        } catch (SdkClientException sce) {
            // 4) SDK 클라이언트 예외(네트워크/DNS/기본 설정 등)
            throw new ApiException(
                    ErrorCode.DOWNLOAD_HTTP_CONNECTION_FAILED,
                    "ECR GetDownloadUrlForLayer 호출 중 클라이언트 오류: " + sce.getMessage(),
                    Map.of("digest", Masking.maskDigest(digest), "repo", repo),
                    sce
            );
        }
    }

    // =========================
    // HTTP download
    // =========================

    /**
     * HTTP GET 요청 전송
     * <p>
     * - withAuth=true이면 Authorization: Basic {token} 헤더 추가
     * - body는 InputStream으로 받아서 스트리밍 처리(메모리 사용 최소화)
     */
    private HttpResponse<InputStream> sendGet(
            String downloadUrl,
            int timeoutSeconds,
            boolean withAuth,
            String basicAuthToken
    ) throws IOException, InterruptedException {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", "ecr-layer-stream-downloader/1.0")
                .GET();

        // presigned URL이 401/403을 줄 때만 붙이는 전략 (기본은 헤더 없이)
        if (withAuth && StringUtils.isNotBlank(basicAuthToken)) {
            rb.header("Authorization", "Basic " + basicAuthToken);
        }

        return http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    // =========================
    // Streaming + digest
    // =========================

    /**
     * InputStream을 파일로 스트리밍 저장하면서 (옵션) SHA-256 digest를 계산
     * <p>
     * - digestOn=true면 SHA-256 계산 후 hex 문자열 반환
     * - digestOn=false면 파일만 저장하고 null 반환(계산 비용 절감)
     */
    private String streamToFileAndDigest(InputStream in, Path tmp, boolean digestOn) throws IOException {
        // 1) digest 사용 여부에 따라 MessageDigest 초기화
        MessageDigest md = null;
        if (digestOn) {
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("SHA-256 MessageDigest 초기화 실패: " + e.getMessage(), e);
            }
        }

        // 2) 파일로 스트리밍 저장(64KB 버퍼)
        //    - CREATE + TRUNCATE로 기존 .part가 있으면 덮어쓰기
        try (OutputStream os = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[64 * 1024];
            int n;

            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;

                // 파일 쓰기
                os.write(buf, 0, n);

                // digest 계산(옵션)
                if (md != null) md.update(buf, 0, n);
            }

            os.flush();
        }

        // 3) digestOn이 아니면 계산 결과 없음(null)
        if (md == null) return null;

        // 4) SHA-256 hex 문자열 반환
        return toHex(md.digest());
    }

    // =========================
    // SHA-256 verify
    // =========================

    /**
     * digest(예: sha256:xxx)와 computedHex를 비교하여 무결성을 검증
     * - mismatch면 tmp 삭제 후 ApiException 발생
     */
    private void verifySha256OrThrow(String digest, String computedHex, Path tmp) {
        // 1) ECR digest는 "sha256:{hex}" 형태이므로 prefix를 제거한 hex만 비교
        String expectedHex = stripSha256Prefix(digest);

        // 2) 계산 결과가 null이면 로직 상 오류 (verifySha256=true인데 computed가 null)
        if (computedHex == null) {
            safeDelete(tmp);
            throw new ApiException(
                    ErrorCode.DOWNLOAD_CORRUPTED_CONTENT,
                    "SHA256 계산 결과가 null입니다(verifySha256=true). digest=" + Masking.maskDigest(digest),
                    Map.of("digest", Masking.maskDigest(digest))
            );
        }

        // 3) 불일치 -> 손상/중간 변조 가능성
        if (!expectedHex.equalsIgnoreCase(computedHex)) {
            safeDelete(tmp);
            throw new ApiException(
                    ErrorCode.DOWNLOAD_DIGEST_MISMATCH,
                    "SHA256 불일치. digest=" + Masking.maskDigest(digest),
                    Map.of(
                            "digest", Masking.maskDigest(digest),
                            "expected", expectedHex,
                            "actual", computedHex
                    )
            );
        }
    }

    // =========================
    // Commit tmp -> target
    // =========================

    /**
     * 임시 파일을 최종 경로로 커밋(move)
     * <p>
     * - ATOMIC_MOVE를 우선 시도(지원되는 FS라면 원자적)
     * - 미지원/실패 시 REPLACE_EXISTING로 폴백
     */
    private void commitTmp(Path tmp, Path targetPath) throws IOException {
        try {
            Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFail) {
            // 일부 파일 시스템/플랫폼에서 ATOMIC_MOVE 미지원
            Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // =========================
    // Retry backoff
    // =========================

    /**
     * 백오프 계산 및 sleep + 로깅
     * <p>
     * Retry.backoffMillis() 정책을 그대로 사용
     */
    private void backoffAndSleep(int attempt, int retries, String digest, String reason) {
        long backoff = Retry.backoffMillis(attempt, backoffBaseMillis, backoffMaxMillis);

        log.warn("Retry download. attempt={}/{}, digest={}, reason={}, backoffMs={}",
                attempt, retries, Masking.maskDigest(digest), reason, backoff);

        Retry.sleepQuiet(backoff);
    }

    // =========================
    // Classification helpers
    // =========================

    /**
     * ECR 예외를 서비스 ErrorCode로 분류
     * - 401/403: unauthorized
     * - 429/throttling: throttled
     * - repo/layer not found: not found
     * - 기타: api failed
     */
    private ErrorCode classifyEcrException(EcrException e) {
        // 1) 공통 분류(Unauthorized/Throttling)
        ErrorCode base = EcrErrorMapper.toErrorCode(e);
        if (base != ErrorCode.DOWNLOAD_ECR_API_FAILED) return base;

        // 2) BlobDownloader 컨텍스트에서만 필요한 세부 분류
        String awsCode = EcrErrorMapper.awsErrorCode(e);
        int sc = (e == null) ? 0 : e.statusCode();

        // GetDownloadUrlForLayer에서 주로 발생하는 예외들(환경마다 awsCode가 다를 수 있음)
        if ("RepositoryNotFoundException".equals(awsCode)) return ErrorCode.DOWNLOAD_ECR_REPOSITORY_NOT_FOUND;
        if ("LayerNotFoundException".equals(awsCode) || "ImageNotFoundException".equals(awsCode)) {
            return ErrorCode.DOWNLOAD_ECR_BLOB_NOT_FOUND;
        }

        if (sc == 404) return ErrorCode.DOWNLOAD_ECR_BLOB_NOT_FOUND;

        return ErrorCode.DOWNLOAD_ECR_API_FAILED;
    }

    /**
     * FileSystemException 메시지 기반으로 ErrorCode 분류
     * - OS/FS마다 메시지 포맷이 달라 “보수적으로” 판단
     */
    private ErrorCode classifyFileSystemException(FileSystemException fse) {
        String reason = safeFsMsg(fse);
        String r = (reason == null) ? "" : reason.toLowerCase();

        if (r.contains("no space")
                || r.contains("not enough space")
                || r.contains("disk full")
                || r.contains("insufficient space")) {
            return ErrorCode.DOWNLOAD_FS_NO_SPACE;
        }

        if (r.contains("permission")
                || r.contains("access is denied")
                || r.contains("denied")) {
            return ErrorCode.DOWNLOAD_FS_PERMISSION_DENIED;
        }

        return ErrorCode.DOWNLOAD_FS_WRITE_FAILED;
    }

    // =========================
    // Small utilities
    // =========================

    /**
     * HTTP status 판정 유틸
     */
    private boolean is2xx(int code) {
        return code >= 200 && code < 300;
    }

    private boolean isUnauthorized(int code) {
        return code == 401 || code == 403;
    }

    private boolean isRetryableStatus(int code) {
        return code == 429 || (code >= 500 && code <= 599);
    }

    private ErrorCode mapHttpFailure(int code) {
        if (isUnauthorized(code)) return ErrorCode.DOWNLOAD_UNAUTHORIZED;
        if (code == 404) return ErrorCode.DOWNLOAD_ECR_BLOB_NOT_FOUND;
        return ErrorCode.DOWNLOAD_HTTP_UNEXPECTED_STATUS;
    }

    /**
     * 임시파일 경로 생성
     * - targetPath + ".part"
     */
    private Path tmpPath(Path targetPath) {
        return Path.of(targetPath + ".part");
    }

    /**
     * blank 방어 + ApiException(요청 오류) 변환
     * - downloader는 infra 계층이지만, 현재 프로젝트 정책상 ApiException으로 통일
     */
    private String requireText(String v, String name) {
        String t = StringUtils.trimToNull(v);
        if (t == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, name + "은(는) 필수입니다.");
        }
        return t;
    }

    /**
     * FileSystemException 메시지 추출(가능하면 reason 우선)
     */
    private String safeFsMsg(FileSystemException e) {
        if (e == null) return null;
        if (StringUtils.isNotBlank(e.getReason())) return e.getReason();
        String m = e.getMessage();
        return (m == null) ? "" : m;
    }

    /**
     * byte[] -> hex 변환
     */
    private String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            int v = x & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    /**
     * "sha256:" 접두어 제거(없으면 원문)
     */
    private String stripSha256Prefix(String digest) {
        if (digest == null) return null;
        return digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
    }

    /**
     * HttpResponse<InputStream>의 body를 안전하게 close
     */
    private void closeQuiet(HttpResponse<InputStream> resp) {
        if (resp == null) return;
        try {
            InputStream b = resp.body();
            if (b != null) b.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 임시 파일 삭제(실패해도 무시)
     */
    private void safeDelete(Path p) {
        try {
            if (p != null) Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }

    /**
     * AWS 에러 메시지 추출
     * - awsErrorDetails.errorMessage 우선
     * - 없으면 exception message
     */
    private String safeAwsMsg(EcrException e) {
        String m = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorMessage() : null;
        if (StringUtils.isBlank(m)) m = e.getMessage();
        return StringUtils.defaultString(m);
    }

    /**
     * URL 호스트 추출(로그/메타데이터용)
     */
    private String safeHost(URI uri) {
        if (uri == null) return "(null)";
        String h = uri.getHost();
        return (h == null) ? "(unknown)" : h;
    }

    // =========================
    // Internal exception type
    // =========================

    /**
     * HTTP status 기반 "재시도 가능" 신호용 내부 예외
     * - 상위 루프에서 동일한 재시도 정책으로 처리
     */
    private static class RetryableHttpException extends RuntimeException {
        final int status;

        private RetryableHttpException(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }
}
