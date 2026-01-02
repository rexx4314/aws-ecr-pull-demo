package dev.rex.demo.ecr.download;

import dev.rex.demo.common.util.Masking;
import dev.rex.demo.common.util.Retry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;

/**
 * ECR Layer 다운로드
 * - URL 획득: GetDownloadUrlForLayer
 * - 저장: HTTP GET 스트리밍
 * - 무결성: SHA256 검증
 * - 재시도: 429/5xx/timeout
 */
@Slf4j
@Component
public class BlobDownloader {

    // HTTP 클라이언트
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // 백오프 설정
    @Value("${app.download.backoffBaseMillis:300}")
    private long backoffBaseMillis;

    @Value("${app.download.backoffMaxMillis:5000}")
    private long backoffMaxMillis;

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
        Objects.requireNonNull(ecr, "ecr");
        Objects.requireNonNull(targetPath, "targetPath");

        String repo = StringUtils.trimToNull(repositoryName);
        if (repo == null) throw new IllegalArgumentException("repositoryName은 필수입니다.");

        String digest = StringUtils.trimToNull(layerDigest);
        if (digest == null) throw new IllegalArgumentException("layerDigest는 필수입니다.");

        int retries = Math.max(1, maxRetries);
        int timeoutSec = (httpTimeoutSeconds <= 0) ? 180 : httpTimeoutSeconds;

        // 1) ECR에서 다운로드 URL 획득
        String downloadUrl = getDownloadUrl(ecr, registryId, repo, digest);

        // 2) 임시 파일(.part) -> 성공 시 rename 시도
        Path tmp = Path.of(targetPath + ".part");
        DownloadLayout.mkdirs(targetPath.getParent());

        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                // 1차: Authorization 없이
                HttpResponse<InputStream> resp = sendGet(downloadUrl, timeoutSec, false, basicAuthToken);
                int code = resp.statusCode();

                // 2차: 401/403이면 Basic 시도
                if ((code == 401 || code == 403) && StringUtils.isNotBlank(basicAuthToken)) {
                    closeQuiet(resp);
                    resp = sendGet(downloadUrl, timeoutSec, true, basicAuthToken);
                    code = resp.statusCode();
                }

                // 재시도 대상 코드
                if (code == 429 || (code >= 500 && code <= 599)) {
                    closeQuiet(resp);
                    throw new RetryableHttpException("retryable http status=" + code);
                }

                // 실패 코드
                if (code < 200 || code >= 300) {
                    URI uri = URI.create(downloadUrl);
                    closeQuiet(resp);
                    throw new IllegalStateException(
                            "HTTP 다운로드 실패 status=" + code +
                                    ", host=" + safeHost(uri) +
                                    ", digest=" + Masking.maskDigest(digest)
                    );
                }

                // 스트리밍 저장 + digest 계산
                String computedHex;
                try (InputStream body = resp.body()) {
                    computedHex = streamToFileAndDigest(body, tmp, verifySha256);
                }

                // sha256 검증
                if (verifySha256) {
                    verifySha256(digest, computedHex, tmp);
                }

                // 커밋(rename)
                commitTmp(tmp, targetPath);

                log.info("Downloaded OK. digest={}, path={}", Masking.maskDigest(digest), targetPath.toAbsolutePath());
                return targetPath;

            } catch (RetryableHttpException re) {
                safeDelete(tmp);
                if (attempt >= retries) {
                    throw new IllegalStateException("HTTP 재시도 초과: " + re.getMessage()
                            + ", digest=" + Masking.maskDigest(digest), re);
                }
                backoffAndSleep(attempt, retries, digest, re.getMessage());

            } catch (HttpTimeoutException te) {
                safeDelete(tmp);
                if (attempt >= retries) {
                    throw new IllegalStateException("HTTP timeout 재시도 초과: " + te.getMessage()
                            + ", digest=" + Masking.maskDigest(digest), te);
                }
                backoffAndSleep(attempt, retries, digest, te.getMessage());

            } catch (InterruptedException ie) {
                safeDelete(tmp);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("다운로드 중단(Interrupted). digest=" + Masking.maskDigest(digest), ie);

            } catch (IOException ioe) {
                safeDelete(tmp);
                if (attempt >= retries) {
                    throw new IllegalStateException("다운로드 I/O 재시도 초과: " + ioe.getMessage()
                            + ", digest=" + Masking.maskDigest(digest), ioe);
                }
                backoffAndSleep(attempt, retries, digest, ioe.getMessage());

            } catch (RuntimeException ex) {
                safeDelete(tmp);
                throw ex;
            }
        }

        throw new IllegalStateException("Unexpected downloader exit. digest=" + Masking.maskDigest(digest));
    }

    // ECR URL 획득
    private String getDownloadUrl(EcrClient ecr, String registryId, String repo, String digest) {
        try {
            GetDownloadUrlForLayerResponse u = ecr.getDownloadUrlForLayer(GetDownloadUrlForLayerRequest.builder()
                    .registryId(registryId)
                    .repositoryName(repo)
                    .layerDigest(digest)
                    .build());

            String url = StringUtils.trimToNull(u.downloadUrl());
            if (url == null) {
                throw new IllegalStateException("GetDownloadUrlForLayer 결과 downloadUrl이 비었습니다. digest=" + Masking.maskDigest(digest));
            }
            return url;

        } catch (EcrException ee) {
            throw new IllegalStateException("ECR GetDownloadUrlForLayer 실패: " + safeAwsMsg(ee), ee);
        }
    }

    // HTTP GET
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

        if (withAuth && StringUtils.isNotBlank(basicAuthToken)) {
            rb.header("Authorization", "Basic " + basicAuthToken);
        }

        return http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    // 스트리밍 저장 + SHA256
    private String streamToFileAndDigest(InputStream in, Path tmp, boolean digestOn) throws IOException {
        MessageDigest md = null;
        if (digestOn) {
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IOException("SHA-256 MessageDigest 초기화 실패: " + e.getMessage(), e);
            }
        }

        try (OutputStream os = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[64 * 1024]; // 64KB
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                os.write(buf, 0, n);
                if (md != null) md.update(buf, 0, n);
            }
            os.flush();
        }

        if (md == null) return null;
        return toHex(md.digest());
    }

    // SHA256 검증
    private void verifySha256(String digest, String computedHex, Path tmp) {
        String expectedHex = stripSha256Prefix(digest);
        if (computedHex == null) {
            safeDelete(tmp);
            throw new IllegalStateException("SHA256 계산 결과가 null입니다(verifySha256=true). digest=" + Masking.maskDigest(digest));
        }
        if (!expectedHex.equalsIgnoreCase(computedHex)) {
            safeDelete(tmp);
            throw new IllegalStateException("SHA256 불일치. digest=" + Masking.maskDigest(digest)
                    + ", expected=" + expectedHex + ", actual=" + computedHex);
        }
    }

    // 임시파일 커밋(rename)
    private void commitTmp(Path tmp, Path targetPath) throws IOException {
        try {
            Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFail) {
            Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // 백오프 로그 + sleep
    private void backoffAndSleep(int attempt, int retries, String digest, String reason) {
        long backoff = Retry.backoffMillis(attempt, backoffBaseMillis, backoffMaxMillis);
        log.warn("Retry download. attempt={}/{}, digest={}, reason={}, backoffMs={}",
                attempt, retries, Masking.maskDigest(digest), reason, backoff);
        Retry.sleepQuiet(backoff);
    }

    private String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            int v = x & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private String stripSha256Prefix(String digest) {
        if (digest == null) return null;
        return digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
    }

    private void closeQuiet(HttpResponse<InputStream> resp) {
        if (resp == null) return;
        try {
            InputStream b = resp.body();
            if (b != null) b.close();
        } catch (Exception ignored) {
        }
    }

    private void safeDelete(Path p) {
        try {
            if (p != null) Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }

    private String safeAwsMsg(EcrException e) {
        String m = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorMessage() : null;
        if (m == null || m.isBlank()) m = e.getMessage();
        return m;
    }

    private String safeHost(URI uri) {
        if (uri == null) return "(null)";
        String h = uri.getHost();
        return (h == null) ? "(unknown)" : h;
    }

    private static class RetryableHttpException extends RuntimeException {
        private RetryableHttpException(String msg) {
            super(msg);
        }
    }
}
