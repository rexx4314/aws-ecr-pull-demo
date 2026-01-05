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
 * ECR 이미지 Blob 병렬 다운로드 Executor
 * <p>
 * 주요 기능
 * - ECR에서 Docker 이미지의 레이어(layer)와 설정(config)을 병렬로 다운로드
 * - 실패 시 재시도 정책 지원
 * - SHA-256 검증 옵션 제공
 * - 부분 성공/실패 처리
 * <p>
 * 설계 특징
 * - CompletionService로 병렬 작업 관리 (순서 무관 수집)
 * - 데몬 스레드 풀로 백그라운드 실행
 * - 실패 시 digest별 ErrorCode 분류 및 요약 제공
 * - 인터럽트/타임아웃 안전 처리
 * <p>
 * 처리 흐름
 * 1) ECR 인증 토큰 획득 (fetchBasicAuthToken)
 * 2) 레이어/설정 다운로드 작업을 병렬 제출 (submitAllTasks)
 * 3) 결과 수집 및 실패 집계 (take().get())
 * 4) 부분 실패 시 예외 발생 (throwIfAnyFailure)
 * 5) 성공 시 다운로드 개수 및 설정 경로 반환
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadExecutor {

    private static final int FAILURE_SUMMARY_LIMIT = 5;

    /**
     * Blob 다운로드 전담 서비스 (HTTP 요청, 재시도, 검증)
     */
    private final BlobDownloader blobDownloader;

    /**
     * 모든 Blob(레이어 + 설정) 병렬 다운로드
     * <p>
     * 입력
     * - ecr: ECR 클라이언트
     * - accountId: AWS 계정 ID
     * - repositoryName: 저장소 이름
     * - layout: 다운로드 경로 레이아웃
     * - layerDigests: 레이어 digest 목록
     * - configDigest: 설정 digest
     * - includeConfig: 설정 다운로드 여부
     * - concurrency: 병렬 스레드 수
     * - httpTimeoutSeconds: HTTP 타임아웃 (초)
     * - maxRetries: 최대 재시도 횟수
     * - verifySha256: SHA-256 검증 여부
     * <p>
     * 출력
     * - 다운로드 성공한 레이어 개수 + 설정 파일 경로
     * <p>
     * 예외
     * - 실패 시 ApiException (부분 실패 포함)
     *
     * @return 다운로드 결과 (레이어 개수, 설정 경로)
     */
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
        // 1) 입력 파라미터 검증
        //    - 필수 값 누락/범위 오류를 초기에 차단하여 이후 오류 원인 추적을 단순화
        validateArgs(ecr, accountId, repositoryName, layout, concurrency, httpTimeoutSeconds, maxRetries);

        // 2) ECR 인증 토큰 획득
        //    - blob 다운로드(HTTP)에서 Authorization: Basic {token} 용도로 사용됨
        //    - 토큰이 없으면 모든 다운로드가 실패하므로 선행 단계로 수행
        String basicToken = fetchBasicAuthToken(ecr, accountId);

        // 3) 병렬 다운로드 스레드 풀 생성(데몬 스레드)
        //    - 요청 단위로 생성/폐기하는 구조라 finally에서 반드시 종료
        ExecutorService pool = newFixedDaemonPool(concurrency, "layer-dl-");

        try {
            // 4) CompletionService
            //    - Future를 “완료되는 순서대로” 수집할 수 있어 대기/집계 로직을 단순화
            CompletionService<SuccessItem> ecs = new ExecutorCompletionService<>(pool);

            // 5) 다운로드 작업 제출
            //    - layer digests 각각 + (옵션에 따라) config digest 1건
            //    - submitAllTasks는 제출된 작업 수를 반환하며, 이는 결과 수집 루프 횟수로 사용
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

            // 6) 결과 수집 및 집계
            //    - 성공: 레이어 카운트 증가 또는 configPath 설정
            //    - 실패: ExecutionException.getCause()를 FailureItem으로 변환하여 누적
            Aggregation agg = collectResults(ecs, submitted);

            // 7) 실패가 존재하면 예외 발생(부분 성공/전체 실패를 ErrorCode로 구분)
            throwIfAnyFailure(agg.failures, agg.okLayers, agg.okConfig);

            // 8) 성공 결과 반환
            return new DownloadOutcome(agg.okLayers, agg.configPath);

        } catch (InterruptedException ie) {
            // 9) 인터럽트 발생 시
            //    - 인터럽트 상태를 복구하고(권장 패턴) 중단으로 처리
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.DOWNLOAD_THREAD_INTERRUPTED, "다운로드 중단됨", null, ie);

        } finally {
            // 10) 스레드 풀 정리
            //     - 작업이 남아있을 수 있으므로 shutdownNow로 즉시 중단 시도
            pool.shutdownNow();
        }
    }

    /**
     * 결과 수집(순서 무관) 및 실패 집계
     */
    private Aggregation collectResults(CompletionService<SuccessItem> ecs, int submitted) throws InterruptedException {
        // 성공 집계
        int okLayers = 0;
        boolean okConfig = false;
        Path configPath = null;

        // 실패 집계
        List<FailureItem> failures = new ArrayList<>();

        // 제출된 작업 수만큼 완료 결과를 수집해야 함
        for (int i = 0; i < submitted; i++) {
            try {
                // take(): 완료된 작업이 나올 때까지 블록(InterruptedException 가능)
                // get(): 작업 내부 예외는 ExecutionException으로 래핑되어 올라옴
                SuccessItem r = ecs.take().get();

                // config 작업은 “단일 파일 경로”가 의미가 있으므로 path를 보관
                // layer 작업은 “개수 집계”가 중요하므로 카운트만 증가
                if (r.isConfig()) {
                    okConfig = true;
                    configPath = r.path();
                } else {
                    okLayers++;
                }
            } catch (ExecutionException ee) {
                // 개별 작업 실패
                // - 원인 예외를 FailureItem으로 변환하여 누적
                // - DigestTaggedException이면 digest/code가 보존됨
                failures.add(toFailureItem(ee.getCause()));
            }
        }

        // 집계 결과를 하나로 반환하여 상위에서 정책(부분 성공/전체 실패)을 적용
        return new Aggregation(okLayers, okConfig, configPath, failures);
    }

    /**
     * 입력 파라미터 검증
     * <p>
     * - 필수값 null/blank 체크
     * - 범위값 양수 체크
     */
    private void validateArgs(
            EcrClient ecr,
            String accountId,
            String repositoryName,
            DownloadLayout layout,
            int concurrency,
            int httpTimeoutSeconds,
            int maxRetries
    ) {
        // 필수값 검증(원인 명확화)
        if (ecr == null) throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "ECR 클라이언트가 null 입니다");
        if (StringUtils.isBlank(accountId)) throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "accountId가 비어있습니다");
        if (StringUtils.isBlank(repositoryName))
            throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "repositoryName이 비어있습니다");
        if (layout == null) throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "layout이 null 입니다");

        // 범위값 검증
        if (concurrency <= 0) throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "concurrency는 1 이상이어야 합니다");
        if (httpTimeoutSeconds <= 0)
            throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "httpTimeoutSeconds는 1 이상이어야 합니다");
        if (maxRetries < 0) throw new ApiException(ErrorCode.DOWNLOAD_TASK_FAILED, "maxRetries는 0 이상이어야 합니다");
    }

    /**
     * 고정 크기 데몬 스레드 풀 생성
     * <p>
     * - 데몬 스레드로 백그라운드 실행
     * - JVM 종료 시 자동 정리
     */
    private ExecutorService newFixedDaemonPool(int concurrency, String namePrefix) {
        // 데몬 스레드로 생성:
        // - 테스트/서버 종료 시 비정상적으로 프로세스가 붙잡히는 것을 완화
        return Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, namePrefix + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * digest 목록 null 방어
     */
    private List<String> safeDigests(List<String> digests) {
        // null이면 빈 리스트를 반환하여 NPE를 방지하고 제출 로직을 단순화
        return (digests == null) ? Collections.emptyList() : digests;
    }

    /**
     * 레이어/설정 다운로드 작업 일괄 제출
     * <p>
     * - 레이어 digest별 작업 제출
     * - 설정 다운로드 여부에 따라 설정 작업 제출
     * <p>
     * 반환: 제출된 작업 수
     */
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
        // 실제로 제출한 작업 수(= 이후 수집해야 할 결과 수)
        int submitted = 0;

        // 1) 레이어 다운로드 작업 제출
        //    - digest는 trimToNull로 정규화(공백/빈 문자열 제거)
        //    - outPath는 digest별로 layout 규칙에 따라 결정
        for (String digest : layerDigests) {
            String d = StringUtils.trimToNull(digest);
            if (d == null) continue;

            // Callable 작업:
            // - downloadOne이 성공하면 SuccessItem 반환
            // - 실패하면 DigestTaggedException을 던져 digest/code를 상위로 보존
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

        // 2) 설정(config) 다운로드 작업 제출(옵션 기반)
        //    - includeConfig=true일 때만 다운로드 대상으로 포함
        //    - configDigest가 빈 값이면 제출하지 않음
        if (includeConfig && StringUtils.isNotBlank(configDigest)) {
            String d = configDigest.trim();

            // config 파일은 레이어와 달리 경로가 고정(layout.configPath())
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

        // 제출된 작업 수 반환(collectResults에서 “몇 번 take()해야 하는지” 결정)
        return submitted;
    }

    /**
     * 실패 항목이 있으면 예외 발생
     * <p>
     * - 부분 성공: DOWNLOAD_PARTIAL_SUCCESS
     * - 전체 실패: DOWNLOAD_TASK_FAILED
     */
    private void throwIfAnyFailure(List<FailureItem> failures, int okLayers, boolean okConfig) {
        // 실패가 없으면 정상 종료
        if (failures == null || failures.isEmpty()) return;

        // 일부라도 성공했는지 판단(부분 성공 vs 전체 실패)
        boolean anySuccess = (okLayers > 0) || okConfig;

        // 실패 코드 정책:
        // - 일부 성공이면 “부분 성공”으로 응답(클라이언트가 재시도/보완 가능)
        // - 전부 실패면 “작업 실패”
        ErrorCode code = anySuccess ? ErrorCode.DOWNLOAD_PARTIAL_SUCCESS : ErrorCode.DOWNLOAD_TASK_FAILED;

        // 실패 요약(최대 N건 + 추가 개수)
        String summary = buildFailureSummary(failures);

        // 상위로 예외 전파(컨트롤러/서비스에서 동일한 에러 응답 생성)
        throw new ApiException(code, summary, null);
    }

    /**
     * 실패 원인을 FailureItem으로 변환
     * <p>
     * - DigestTaggedException: digest + ErrorCode 보존
     * - 일반 예외: digest 없음 + ErrorCode 분류
     */
    private FailureItem toFailureItem(Throwable cause) {
        // 원인 정보가 없으면 “알 수 없는 실패”로 처리
        if (cause == null) {
            return new FailureItem(null, ErrorCode.DOWNLOAD_TASK_FAILED, "");
        }

        // 작업 내부에서 digest/code를 보존한 예외는 그대로 반영(요약에 digest 포함 가능)
        if (cause instanceof DigestTaggedException dte) {
            return new FailureItem(dte.digest, dte.code, safeMsg(dte.getCause()));
        }

        // 그 외는 digest를 알 수 없으므로 null로 두고 ErrorCode만 분류
        return new FailureItem(null, classifyThrowable(cause), safeMsg(cause));
    }

    /**
     * ECR 인증 토큰 획득
     * <p>
     * 실패 처리
     * - 401/403: DOWNLOAD_UNAUTHORIZED
     * - 429/Throttling: DOWNLOAD_ECR_THROTTLED
     * - 기타: DOWNLOAD_AUTH_TOKEN_FAILED
     */
    private String fetchBasicAuthToken(EcrClient ecr, String accountId) {
        try {
            // 1) ECR AuthorizationToken 조회
            // - authorizationToken은 Base64(username:password) 형태
            // - 실제 blob 다운로드에서 Basic 인증으로 사용됨(BlobDownloader에서 사용)
            GetAuthorizationTokenResponse auth = ecr.getAuthorizationToken(
                    GetAuthorizationTokenRequest.builder()
                            .registryIds(accountId)
                            .build()
            );

            // 2) 응답 검증: authorizationData가 비어있으면 정상 토큰이 없음
            if (auth.authorizationData() == null || auth.authorizationData().isEmpty()) {
                throw new ApiException(ErrorCode.DOWNLOAD_AUTH_TOKEN_FAILED, "인증 토큰 조회 실패(authorizationData 비어있음)");
            }

            // 3) 토큰 추출(첫 번째 항목 사용)
            String token = StringUtils.trimToNull(auth.authorizationData().get(0).authorizationToken());
            if (token == null) {
                // 토큰 문자열이 비어있으면 별도 코드로 구분(분석/모니터링 용이)
                throw new ApiException(ErrorCode.DOWNLOAD_AUTH_TOKEN_EMPTY, "토큰이 비어있습니다");
            }

            return token;

        } catch (EcrException e) {
            // 4) ECR API 실패: statusCode/에러코드로 정책적으로 분류
            int sc = e.statusCode();

            // 권한/인증 오류
            if (sc == 401 || sc == 403) {
                throw new ApiException(ErrorCode.DOWNLOAD_UNAUTHORIZED, "ECR 인증/권한 오류 (status=" + sc + ")", null, e);
            }

            // 쓰로틀링(요청 제한)
            String awsErr = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : null;
            if (sc == 429 || "ThrottlingException".equals(awsErr) || "TooManyRequestsException".equals(awsErr)) {
                throw new ApiException(ErrorCode.DOWNLOAD_ECR_THROTTLED, "ECR 요청 제한(Throttling) (status=" + sc + ")", null, e);
            }

            // 기타 ECR 실패
            throw new ApiException(ErrorCode.DOWNLOAD_AUTH_TOKEN_FAILED, "ECR 인증 토큰 API 실패 (status=" + sc + ")", null, e);

        } catch (SdkClientException e) {
            // 5) 네트워크/SDK 클라이언트 레벨 오류
            throw new ApiException(ErrorCode.DOWNLOAD_HTTP_CONNECTION_FAILED, "ECR 인증 토큰 요청 중 클라이언트 오류", null, e);
        }
    }

    /**
     * 단일 Blob 다운로드 (레이어 또는 설정)
     * <p>
     * - 성공 시 SuccessItem 반환
     * - 실패 시 DigestTaggedException 발생 (digest + ErrorCode 보존)
     */
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
            // 1) 실제 다운로드 수행
            // - BlobDownloader가 HTTP 요청/재시도/sha 검증을 담당
            // - 성공 시 저장된 파일 Path를 반환
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

            // 2) 성공 결과 구성
            // - 상위 집계에서 config인지 layer인지 구분하여 처리
            return new SuccessItem(digest, out, isConfig);

        } catch (ApiException ae) {
            // 3) 도메인/정책적으로 이미 분류된 ErrorCode는 유지한 채 digest와 함께 상위로 전달
            throw new DigestTaggedException(digest, ae.code(), ae);

        } catch (Exception ex) {
            // 4) 분류되지 않은 예외는 classifyThrowable로 ErrorCode를 매핑하여 digest와 함께 전달
            throw new DigestTaggedException(digest, classifyThrowable(ex), ex);
        }
    }

    /**
     * 예외를 ErrorCode로 분류
     */
    private ErrorCode classifyThrowable(Throwable t) {
        // 기본값: 원인 불명 또는 분류 불가
        if (t == null) return ErrorCode.DOWNLOAD_TASK_FAILED;

        // 네트워크 타임아웃(HTTP 레벨)
        if (t instanceof TimeoutException) return ErrorCode.DOWNLOAD_HTTP_TIMEOUT;

        // 인터럽트(요청 취소/서버 종료 등)
        if (t instanceof InterruptedException) return ErrorCode.DOWNLOAD_THREAD_INTERRUPTED;

        // ECR API 예외: statusCode/에러코드 기반 분류
        if (t instanceof EcrException e) {
            int sc = e.statusCode();

            // 권한/인증
            if (sc == 401 || sc == 403) return ErrorCode.DOWNLOAD_UNAUTHORIZED;

            // 쓰로틀링
            String awsErr = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : null;
            if (sc == 429 || "ThrottlingException".equals(awsErr) || "TooManyRequestsException".equals(awsErr)) {
                return ErrorCode.DOWNLOAD_ECR_THROTTLED;
            }

            // blob 미존재(잘못된 digest, 정합성 깨짐, 레포 변동 등)
            if (sc == 404) return ErrorCode.DOWNLOAD_ECR_BLOB_NOT_FOUND;

            // 그 외는 ECR API 실패로 처리
            return ErrorCode.DOWNLOAD_ECR_API_FAILED;
        }

        // SDK 클라이언트/네트워크 계열 실패
        if (t instanceof SdkClientException) {
            return ErrorCode.DOWNLOAD_HTTP_CONNECTION_FAILED;
        }

        // 나머지 예외는 일반 작업 실패로 처리
        return ErrorCode.DOWNLOAD_TASK_FAILED;
    }

    /**
     * 예외 메시지 안전 추출 (최대 300자)
     */
    private String safeMsg(Throwable t) {
        // null 방지
        if (t == null) return "";

        // 메시지가 없으면 toString으로 대체, 너무 길면 abbreviate로 축약(로그/응답 크기 관리)
        String msg = (t.getMessage() != null) ? t.getMessage() : t.toString();
        return StringUtils.abbreviate(msg, 300);
    }

    /**
     * 실패 항목 요약 생성
     * <p>
     * - 최대 5개 실패 항목 표시
     * - 형식: digest [ErrorCode]: message
     * - 추가 실패는 개수만 표시
     */
    private String buildFailureSummary(List<FailureItem> failures) {
        StringBuilder sb = new StringBuilder();

        // 최대 N건만 출력하여 메시지가 과도하게 길어지는 것을 방지
        int limit = Math.min(FAILURE_SUMMARY_LIMIT, failures.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" | ");
            FailureItem f = failures.get(i);

            // digest는 없을 수 있으므로 unknown으로 표시(예: 작업 스케줄링/알 수 없는 예외)
            sb.append(f.digest != null ? f.digest : "unknown")
                    .append(" [").append(f.code).append("]")
                    .append(": ").append(f.message);
        }

        // 나머지 실패는 개수로만 표시(응답/로그 폭주 방지)
        if (failures.size() > FAILURE_SUMMARY_LIMIT) {
            sb.append(" | ... (+").append(failures.size() - FAILURE_SUMMARY_LIMIT).append(" more)");
        }

        return sb.toString();
    }

    /**
     * 다운로드 결과 DTO
     *
     * @param downloadedLayerCount 다운로드된 레이어 수
     * @param configPath           설정 파일 경로
     */
    public record DownloadOutcome(int downloadedLayerCount, Path configPath) {
    }

    /**
     * 단건 다운로드 성공 항목
     */
    private record SuccessItem(String digest, Path path, boolean isConfig) {
    }

    /**
     * 단건 다운로드 실패 항목
     */
    private record FailureItem(String digest, ErrorCode code, String message) {
    }

    /**
     * 결과 집계용 내부 DTO
     */
    private record Aggregation(int okLayers, boolean okConfig, Path configPath, List<FailureItem> failures) {
    }

    /**
     * digest + ErrorCode를 보존하는 내부 예외
     * <p>
     * - 병렬 작업에서 발생한 예외를 digest와 함께 상위로 전달
     * - ExecutionException.getCause()로 추출 후 FailureItem으로 변환
     */
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
