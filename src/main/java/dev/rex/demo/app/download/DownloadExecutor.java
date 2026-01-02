package dev.rex.demo.app.download;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.infra.fs.BlobDownloader;
import dev.rex.demo.infra.fs.DownloadLayout;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.EcrException;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenRequest;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * ECR에서 Docker 이미지의 레이어와 설정을 병렬로 다운로드하는 Executor
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadExecutor {

    private final BlobDownloader blobDownloader;

    public DownloadOutcome downloadAllBlobs(
            EcrClient ecr,
            String accountId,
            String repositoryName,
            DownloadLayout layout,
            List<String> layerDigests,
            String configDigest,
            boolean includeConfig,
            int concurrency,
            int httpTimeoutSeconds,
            int maxRetries,
            boolean verifySha256
    ) {
        // 입력 방어
        validateArgs(ecr, accountId, repositoryName, layout, concurrency, httpTimeoutSeconds, maxRetries);

        // 토큰 획득
        String basicToken = fetchBasicAuthToken(ecr, accountId);

        // 풀 생성
        ExecutorService pool = newFixedDaemonPool(concurrency, "layer-dl-");

        // 결과 집계
        int okLayers = 0;
        boolean okConfig = false;
        Path configPath = null;

        // 실패 수집
        List<FailureItem> failures = new ArrayList<>();

        try {
            // 큐 준비
            CompletionService<SuccessItem> ecs = new ExecutorCompletionService<>(pool);

            // 작업 제출
            int submitted = submitAllTasks(
                    ecs,
                    ecr,
                    accountId,
                    repositoryName,
                    layout,
                    safeDigests(layerDigests),
                    StringUtils.trimToNull(configDigest),
                    includeConfig,
                    basicToken,
                    httpTimeoutSeconds,
                    maxRetries,
                    verifySha256
            );

            // 결과 수집
            for (int i = 0; i < submitted; i++) {
                try {
                    SuccessItem r = ecs.take().get();
                    if (r.isConfig()) {
                        okConfig = true;
                        configPath = r.path();
                    } else {
                        okLayers++;
                    }
                } catch (ExecutionException ee) {
                    failures.add(toFailureItem(ee.getCause()));
                }
            }

            // 실패 처리
            throwIfAnyFailure(failures, okLayers, okConfig);

            // 성공 반환
            return new DownloadOutcome(okLayers, configPath);

        } catch (InterruptedException ie) {
            // 인터럽트 복구
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.DOWNLOAD_THREAD_INTERRUPTED, "다운로드 중단됨", null, ie);
        } finally {
            // 자원 정리
            pool.shutdownNow();
        }
    }

    private void validateArgs(
            EcrClient ecr,
            String accountId,
            String repositoryName,
            DownloadLayout layout,
            int concurrency,
            int httpTimeoutSeconds,
            int maxRetries
    ) {
        // 필수값 확인
        if (ecr == null) throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "ECR 클라이언트가 null 입니다");
        if (StringUtils.isBlank(accountId)) throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "accountId가 비어있습니다");
        if (StringUtils.isBlank(repositoryName))
            throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "repositoryName이 비어있습니다");
        if (layout == null) throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "layout이 null 입니다");

        // 범위 확인
        if (concurrency <= 0) throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "concurrency는 1 이상이어야 합니다");
        if (httpTimeoutSeconds <= 0)
            throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "httpTimeoutSeconds는 1 이상이어야 합니다");
        if (maxRetries < 0) throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "maxRetries는 0 이상이어야 합니다");
    }

    private ExecutorService newFixedDaemonPool(int concurrency, String namePrefix) {
        // 데몬 스레드
        return Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, namePrefix + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    private List<String> safeDigests(List<String> digests) {
        // null 방지
        return (digests == null) ? Collections.emptyList() : digests;
    }

    private int submitAllTasks(
            CompletionService<SuccessItem> ecs,
            EcrClient ecr,
            String accountId,
            String repositoryName,
            DownloadLayout layout,
            List<String> layerDigests,
            String configDigest,
            boolean includeConfig,
            String basicToken,
            int httpTimeoutSeconds,
            int maxRetries,
            boolean verifySha256
    ) {
        // 제출 카운트
        int submitted = 0;

        // 레이어 제출
        for (String digest : layerDigests) {
            String d = StringUtils.trimToNull(digest);
            if (d == null) continue;
            ecs.submit(() -> downloadOne(
                    ecr,
                    accountId,
                    repositoryName,
                    d,
                    layout.blobPathForDigest(d),
                    basicToken,
                    httpTimeoutSeconds,
                    maxRetries,
                    verifySha256,
                    false
            ));
            submitted++;
        }

        // config 제출
        if (includeConfig && StringUtils.isNotBlank(configDigest)) {
            String d = configDigest.trim();
            ecs.submit(() -> downloadOne(
                    ecr,
                    accountId,
                    repositoryName,
                    d,
                    layout.configPath(),
                    basicToken,
                    httpTimeoutSeconds,
                    maxRetries,
                    verifySha256,
                    true
            ));
            submitted++;
        }

        // 제출 수 반환
        return submitted;
    }

    private void throwIfAnyFailure(List<FailureItem> failures, int okLayers, boolean okConfig) {
        // 실패 없음
        if (failures == null || failures.isEmpty()) return;

        // 부분 성공 여부
        boolean anySuccess = (okLayers > 0) || okConfig;

        // 오류 코드 선택
        ErrorCode code = anySuccess ? ErrorCode.DOWNLOAD_PARTIAL_SUCCESS : ErrorCode.DOWNLOAD_TASK_FAILED;

        // 요약 생성
        String summary = buildFailureSummary(failures);

        // 예외 발생
        throw new ApiException(code, summary, null);
    }

    private FailureItem toFailureItem(Throwable cause) {
        // 원인 없음
        if (cause == null) {
            return new FailureItem(null, ErrorCode.DOWNLOAD_TASK_FAILED, "");
        }

        // 태그 예외
        if (cause instanceof DigestTaggedException dte) {
            return new FailureItem(dte.digest, dte.code, safeMsg(dte.getCause()));
        }

        // 일반 예외
        return new FailureItem(null, classifyThrowable(cause), safeMsg(cause));
    }

    private String fetchBasicAuthToken(EcrClient ecr, String accountId) {
        try {
            // 토큰 요청
            GetAuthorizationTokenResponse auth = ecr.getAuthorizationToken(
                    GetAuthorizationTokenRequest.builder()
                            .registryIds(accountId)
                            .build()
            );

            // 응답 검증
            if (auth.authorizationData() == null || auth.authorizationData().isEmpty()) {
                throw new ApiException(ErrorCode.DOWNLOAD_AUTH_TOKEN_FAILED, "인증 토큰 조회 실패(authorizationData 비어있음)");
            }

            // 토큰 추출
            String token = StringUtils.trimToNull(auth.authorizationData().get(0).authorizationToken());
            if (token == null) {
                throw new ApiException(ErrorCode.DOWNLOAD_AUTH_TOKEN_EMPTY, "토큰이 비어있습니다");
            }

            // 정상 반환
            return token;

        } catch (EcrException e) {
            // 상태코드 분기
            int sc = e.statusCode();

            // 권한 오류
            if (sc == 401 || sc == 403) {
                throw new ApiException(ErrorCode.DOWNLOAD_UNAUTHORIZED, "ECR 인증/권한 오류 (status=" + sc + ")", null, e);
            }

            // 쓰로틀링
            String awsErr = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : null;
            if (sc == 429 || "ThrottlingException".equals(awsErr) || "TooManyRequestsException".equals(awsErr)) {
                throw new ApiException(ErrorCode.DOWNLOAD_ECR_THROTTLED, "ECR 요청 제한(Throttling) (status=" + sc + ")", null, e);
            }

            // 기타 실패
            throw new ApiException(ErrorCode.DOWNLOAD_AUTH_TOKEN_FAILED, "ECR 인증 토큰 API 실패 (status=" + sc + ")", null, e);

        } catch (SdkClientException e) {
            // 네트워크/클라이언트 오류
            throw new ApiException(ErrorCode.DOWNLOAD_HTTP_CONNECTION_FAILED, "ECR 인증 토큰 요청 중 클라이언트 오류", null, e);
        }
    }

    private SuccessItem downloadOne(
            EcrClient ecr,
            String registryId,
            String repositoryName,
            String digest,
            Path outPath,
            String basicToken,
            int httpTimeoutSeconds,
            int maxRetries,
            boolean verify,
            boolean isConfig
    ) {
        try {
            // 단건 다운로드
            Path out = blobDownloader.downloadLayer(
                    ecr,
                    registryId,
                    repositoryName,
                    digest,
                    outPath,
                    basicToken,
                    httpTimeoutSeconds,
                    maxRetries,
                    verify
            );

            // 성공 반환
            return new SuccessItem(digest, out, isConfig);

        } catch (ApiException ae) {
            // 코드 보존
            throw new DigestTaggedException(digest, ae.code(), ae);

        } catch (Exception ex) {
            // 코드 분류
            throw new DigestTaggedException(digest, classifyThrowable(ex), ex);
        }
    }

    private ErrorCode classifyThrowable(Throwable t) {
        // 기본값
        if (t == null) return ErrorCode.DOWNLOAD_TASK_FAILED;

        // 타임아웃
        if (t instanceof TimeoutException) return ErrorCode.DOWNLOAD_HTTP_TIMEOUT;

        // 인터럽트
        if (t instanceof InterruptedException) return ErrorCode.DOWNLOAD_THREAD_INTERRUPTED;

        // ECR 예외
        if (t instanceof EcrException e) {
            int sc = e.statusCode();

            // 권한 오류
            if (sc == 401 || sc == 403) return ErrorCode.DOWNLOAD_UNAUTHORIZED;

            // 쓰로틀링
            String awsErr = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : null;
            if (sc == 429 || "ThrottlingException".equals(awsErr) || "TooManyRequestsException".equals(awsErr)) {
                return ErrorCode.DOWNLOAD_ECR_THROTTLED;
            }

            // 미존재
            if (sc == 404) return ErrorCode.DOWNLOAD_ECR_BLOB_NOT_FOUND;

            // 기타
            return ErrorCode.DOWNLOAD_ECR_API_FAILED;
        }

        // 클라이언트 예외
        if (t instanceof SdkClientException) {
            return ErrorCode.DOWNLOAD_HTTP_CONNECTION_FAILED;
        }

        // 일반 실패
        return ErrorCode.DOWNLOAD_TASK_FAILED;
    }

    private String safeMsg(Throwable t) {
        // null 방지
        if (t == null) return "";

        // 메시지 축약
        String msg = (t.getMessage() != null) ? t.getMessage() : t.toString();
        return StringUtils.abbreviate(msg, 300);
    }

    private String buildFailureSummary(List<FailureItem> failures) {
        // 최대 5개 요약
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, failures.size()); i++) {
            if (i > 0) sb.append(" | ");
            FailureItem f = failures.get(i);
            sb.append(f.digest != null ? f.digest : "unknown")
                    .append(" [").append(f.code).append("]")
                    .append(": ").append(f.message);
        }

        // 추가 개수
        if (failures.size() > 5) {
            sb.append(" | ... (+").append(failures.size() - 5).append(" more)");
        }

        return sb.toString();
    }

    public record DownloadOutcome(int downloadedLayerCount, Path configPath) {
    }

    private record SuccessItem(String digest, Path path, boolean isConfig) {
    }

    private record FailureItem(String digest, ErrorCode code, String message) {
    }

    private static class DigestTaggedException extends RuntimeException {
        final String digest;
        final ErrorCode code;

        DigestTaggedException(String digest, ErrorCode code, Throwable cause) {
            super(cause);
            this.digest = digest;
            this.code = (code != null) ? code : ErrorCode.DOWNLOAD_TASK_FAILED;
        }
    }
}
