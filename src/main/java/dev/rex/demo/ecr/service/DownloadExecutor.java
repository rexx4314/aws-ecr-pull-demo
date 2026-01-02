package dev.rex.demo.ecr.service;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.ecr.download.BlobDownloader;
import dev.rex.demo.ecr.download.DownloadLayout;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 다운로드 실행
 * - authToken 획득
 * - 병렬 다운로드
 * - 실패 집계
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
        // Basic 토큰 조회
        String basicToken = fetchBasicAuthToken(ecr, accountId);

        ThreadFactory tf = new NamedThreadFactory("layer-dl");
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, tf);

        int downloadedOk = 0;
        Path configPath = null;
        List<FailureItem> failures = new ArrayList<>();

        try {
            CompletionService<SuccessItem> ecs = new ExecutorCompletionService<>(pool);

            int submitted = 0;

            // layer 제출
            for (String d : layerDigests) {
                final String digest = StringUtils.trimToNull(d);
                if (digest == null) continue;

                ecs.submit(() -> downloadOne(
                        ecr, accountId, repositoryName,
                        digest, layout.blobPathForDigest(digest),
                        basicToken, httpTimeoutSeconds, maxRetries, verifySha256,
                        false
                ));
                submitted++;
            }

            // config 제출
            final boolean submitConfig = includeConfig && StringUtils.isNotBlank(configDigest);
            if (submitConfig) {
                final String cd = StringUtils.trimToNull(configDigest);
                ecs.submit(() -> downloadOne(
                        ecr, accountId, repositoryName,
                        cd, layout.configPath(),
                        basicToken, httpTimeoutSeconds, maxRetries, verifySha256,
                        true
                ));
                submitted++;
            }

            // 결과 수집
            for (int i = 0; i < submitted; i++) {
                Future<SuccessItem> f = ecs.take();
                try {
                    SuccessItem ok = f.get();
                    if (ok.isConfig()) {
                        configPath = ok.path();
                    } else {
                        downloadedOk++;
                    }
                } catch (ExecutionException ee) {
                    Throwable cause = (ee.getCause() != null) ? ee.getCause() : ee;
                    if (cause instanceof DigestTaggedException dte) {
                        failures.add(new FailureItem(dte.digest(), safeMsg(dte.getCause()), dte.getCause()));
                    } else {
                        failures.add(new FailureItem(null, safeMsg(cause), cause));
                    }
                }
            }

            // 실패 집계
            if (!failures.isEmpty()) {
                String summary = buildFailureSummary(failures, 5);
                throw new ApiException(
                        ErrorCode.DOWNLOAD_FAILED,
                        "다운로드 실패(집계): okLayers=" + downloadedOk + ", failCount=" + failures.size() + ", details=" + summary,
                        Map.of("okLayers", downloadedOk, "failCount", failures.size(), "sample", summary)
                );
            }

            return new DownloadOutcome(downloadedOk, configPath);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.DOWNLOAD_FAILED, "다운로드 중단(Interrupted).", null, ie);
        } finally {
            pool.shutdownNow(); // 빠른 종료
        }
    }

    // Basic 토큰 획득
    private String fetchBasicAuthToken(EcrClient ecr, String accountId) {
        GetAuthorizationTokenResponse auth = ecr.getAuthorizationToken(GetAuthorizationTokenRequest.builder()
                .registryIds(accountId)
                .build());

        if (auth.authorizationData() == null || auth.authorizationData().isEmpty()) {
            throw new ApiException(ErrorCode.DOWNLOAD_FAILED, "GetAuthorizationToken 결과가 비어 있습니다.");
        }
        AuthorizationData ad = auth.authorizationData().get(0);

        String token = StringUtils.trimToNull(ad.authorizationToken());
        if (token == null) throw new ApiException(ErrorCode.DOWNLOAD_FAILED, "authorizationToken이 비어 있습니다.");

        return token;
    }

    // 단일 blob 다운로드
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
            return new SuccessItem(digest, out, isConfig);
        } catch (Exception ex) {
            throw new DigestTaggedException(digest, ex);
        }
    }

    private String safeMsg(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        if (m == null) m = t.toString();
        m = StringUtils.trimToEmpty(m);
        return (m.length() > 300) ? m.substring(0, 300) : m;
    }

    private String buildFailureSummary(List<FailureItem> failures, int maxItems) {
        if (failures == null || failures.isEmpty()) return "";
        int limit = Math.min(maxItems, failures.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            FailureItem f = failures.get(i);
            if (i > 0) sb.append(" | ");
            sb.append("#").append(i + 1).append(":");
            if (StringUtils.isNotBlank(f.digest())) sb.append("digest=").append(f.digest()).append(",");
            sb.append("msg=").append(f.message());
        }
        if (failures.size() > limit) {
            sb.append(" | ...(+").append(failures.size() - limit).append(" more)");
        }
        return sb.toString();
    }

    public record DownloadOutcome(int downloadedLayerCount, Path configPath) {
    }

    private record SuccessItem(String digest, Path path, boolean isConfig) {
    }

    private record FailureItem(String digest, String message, Throwable cause) {
    }

    private static class DigestTaggedException extends RuntimeException {
        private final String digest;

        private DigestTaggedException(String digest, Throwable cause) {
            super(cause);
            this.digest = digest;
        }

        public String digest() {
            return digest;
        }
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger(1);

        private NamedThreadFactory(String prefix) {
            this.prefix = (StringUtils.isBlank(prefix)) ? "pool" : prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName(prefix + "-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
