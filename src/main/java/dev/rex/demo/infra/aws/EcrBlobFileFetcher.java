//package dev.rex.demo.infra.aws;
//
//import dev.rex.demo.common.util.Masking;
//import dev.rex.demo.common.util.Retry;
//import lombok.extern.slf4j.Slf4j;
//import org.apache.commons.lang3.StringUtils;
//import org.springframework.stereotype.Component;
//import software.amazon.awssdk.services.ecr.EcrClient;
//import software.amazon.awssdk.services.ecr.model.EcrException;
//import software.amazon.awssdk.services.ecr.model.GetDownloadUrlForLayerRequest;
//import software.amazon.awssdk.services.ecr.model.GetDownloadUrlForLayerResponse;
//
//import java.io.IOException;
//import java.io.InputStream;
//import java.io.OutputStream;
//import java.net.URI;
//import java.net.http.HttpClient;
//import java.net.http.HttpRequest;
//import java.net.http.HttpResponse;
//import java.net.http.HttpTimeoutException;
//import java.nio.file.*;
//import java.security.MessageDigest;
//import java.time.Duration;
//import java.util.Objects;
//
///**
// * ECR blob 다운로드(파일)
// * - registry digest(sha256:) 검증 옵션
// * - 대용량 스트리밍 저장
// */
//@Slf4j
//@Component
//public class EcrBlobFileFetcher {
//
//    // HTTP 클라이언트
//    private final HttpClient http = HttpClient.newBuilder()
//            .followRedirects(HttpClient.Redirect.NORMAL)
//            .connectTimeout(Duration.ofSeconds(10))
//            .build();
//
//    // 재시도 백오프
//    private final long backoffBaseMillis = 300;
//    private final long backoffMaxMillis = 5000;
//
//    public Path fetchBlobToFile(
//            EcrClient ecr,
//            String registryId,
//            String repositoryName,
//            String digestSha256,
//            Path targetFile,
//            int httpTimeoutSeconds,
//            int maxRetries,
//            boolean verifyRegistrySha256
//    ) {
//        Objects.requireNonNull(ecr, "ecr");
//        Objects.requireNonNull(targetFile, "targetFile");
//
//        String repo = StringUtils.trimToNull(repositoryName);
//        if (repo == null) throw new IllegalArgumentException("repositoryName은 필수입니다.");
//
//        String digest = StringUtils.trimToNull(digestSha256);
//        if (digest == null) throw new IllegalArgumentException("digestSha256은 필수입니다.");
//
//        int retries = Math.max(1, maxRetries);
//        int timeoutSec = (httpTimeoutSeconds <= 0) ? 180 : httpTimeoutSeconds;
//
//        // downloadUrl 획득
//        String downloadUrl;
//        try {
//            GetDownloadUrlForLayerResponse u = ecr.getDownloadUrlForLayer(GetDownloadUrlForLayerRequest.builder()
//                    .registryId(registryId)
//                    .repositoryName(repo)
//                    .layerDigest(digest)
//                    .build());
//            downloadUrl = StringUtils.trimToNull(u.downloadUrl());
//        } catch (EcrException ee) {
//            throw new IllegalStateException("ECR GetDownloadUrlForLayer 실패: " + safeAwsMsg(ee), ee);
//        }
//
//        if (downloadUrl == null) {
//            throw new IllegalStateException("downloadUrl이 비었습니다. digest=" + Masking.maskDigest(digest));
//        }
//
//        // 임시 파일(.part)
//        Path tmp = Path.of(targetFile + ".part");
//        mkdirs(targetFile.getParent());
//
//        for (int attempt = 1; attempt <= retries; attempt++) {
//            try {
//                HttpResponse<InputStream> resp = sendGet(downloadUrl, timeoutSec);
//                int code = resp.statusCode();
//
//                if (code == 429 || (code >= 500 && code <= 599)) {
//                    closeQuiet(resp);
//                    throw new RetryableHttpException("retryable http status=" + code);
//                }
//                if (code < 200 || code >= 300) {
//                    URI uri = URI.create(downloadUrl);
//                    closeQuiet(resp);
//                    throw new IllegalStateException("HTTP 실패 status=" + code + ", host=" + safeHost(uri));
//                }
//
//                String computed = streamToFileAndSha256(resp.body(), tmp, verifyRegistrySha256);
//
//                if (verifyRegistrySha256) {
//                    String expected = stripSha256Prefix(digest);
//                    if (!expected.equalsIgnoreCase(computed)) {
//                        safeDelete(tmp);
//                        throw new IllegalStateException("registry sha256 불일치. expected=" + expected + ", actual=" + computed);
//                    }
//                }
//
//                moveCommit(tmp, targetFile);
//                return targetFile;
//
//            } catch (RetryableHttpException re) {
//                safeDelete(tmp);
//                if (attempt >= retries) throw new IllegalStateException("재시도 초과: " + re.getMessage(), re);
//                long backoff = Retry.backoffMillis(attempt, backoffBaseMillis, backoffMaxMillis);
//                Retry.sleepQuiet(backoff);
//
//            } catch (HttpTimeoutException te) {
//                safeDelete(tmp);
//                if (attempt >= retries) throw new IllegalStateException("timeout 재시도 초과: " + te.getMessage(), te);
//                long backoff = Retry.backoffMillis(attempt, backoffBaseMillis, backoffMaxMillis);
//                Retry.sleepQuiet(backoff);
//
//            } catch (IOException ioe) {
//                safeDelete(tmp);
//                if (attempt >= retries) throw new IllegalStateException("I/O 재시도 초과: " + ioe.getMessage(), ioe);
//                long backoff = Retry.backoffMillis(attempt, backoffBaseMillis, backoffMaxMillis);
//                Retry.sleepQuiet(backoff);
//
//            } catch (InterruptedException ie) {
//                safeDelete(tmp);
//                Thread.currentThread().interrupt();
//                throw new IllegalStateException("중단됨(Interrupted).", ie);
//            }
//        }
//
//        throw new IllegalStateException("Unexpected exit. digest=" + Masking.maskDigest(digest));
//    }
//
//    private HttpResponse<InputStream> sendGet(String downloadUrl, int timeoutSeconds) throws IOException, InterruptedException {
//        HttpRequest req = HttpRequest.newBuilder()
//                .uri(URI.create(downloadUrl))
//                .timeout(Duration.ofSeconds(timeoutSeconds))
//                .header("User-Agent", "ecr-blob-fetcher/1.0")
//                .GET()
//                .build();
//        return http.send(req, HttpResponse.BodyHandlers.ofInputStream());
//    }
//
//    private String streamToFileAndSha256(InputStream in, Path tmp, boolean digestOn) throws IOException {
//        MessageDigest md = null;
//        if (digestOn) {
//            try {
//                md = MessageDigest.getInstance("SHA-256");
//            } catch (Exception e) {
//                throw new IOException("SHA-256 초기화 실패: " + e.getMessage(), e);
//            }
//        }
//
//        try (InputStream is = in;
//             OutputStream os = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
//
//            byte[] buf = new byte[64 * 1024];
//            int n;
//            while ((n = is.read(buf)) >= 0) {
//                if (n == 0) continue;
//                os.write(buf, 0, n);
//                if (md != null) md.update(buf, 0, n);
//            }
//            os.flush();
//        }
//
//        if (md == null) return null;
//        return toHex(md.digest());
//    }
//
//    private void moveCommit(Path tmp, Path target) throws IOException {
//        try {
//            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
//        } catch (IOException atomicFail) {
//            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
//        }
//    }
//
//    private void mkdirs(Path dir) {
//        if (dir == null) return;
//        try {
//            Files.createDirectories(dir);
//        } catch (IOException e) {
//            throw new IllegalStateException("디렉토리 생성 실패: " + dir + ", err=" + e.getMessage(), e);
//        }
//    }
//
//    private void safeDelete(Path p) {
//        try {
//            if (p != null) Files.deleteIfExists(p);
//        } catch (Exception ignored) {
//        }
//    }
//
//    private void closeQuiet(HttpResponse<InputStream> resp) {
//        if (resp == null) return;
//        try {
//            InputStream b = resp.body();
//            if (b != null) b.close();
//        } catch (Exception ignored) {
//        }
//    }
//
//    private String safeAwsMsg(EcrException e) {
//        String m = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorMessage() : null;
//        if (m == null || m.isBlank()) m = e.getMessage();
//        return m;
//    }
//
//    private String stripSha256Prefix(String digest) {
//        if (digest == null) return null;
//        return digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
//    }
//
//    private String toHex(byte[] b) {
//        StringBuilder sb = new StringBuilder(b.length * 2);
//        for (byte x : b) {
//            int v = x & 0xff;
//            if (v < 16) sb.append('0');
//            sb.append(Integer.toHexString(v));
//        }
//        return sb.toString();
//    }
//
//    private String safeHost(URI uri) {
//        if (uri == null) return "(null)";
//        String h = uri.getHost();
//        return (h == null) ? "(unknown)" : h;
//    }
//
//    private static class RetryableHttpException extends RuntimeException {
//        private RetryableHttpException(String msg) {
//            super(msg);
//        }
//    }
//}
