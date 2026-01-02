package dev.rex.demo.domain.image;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.zip.GZIPInputStream;

/**
 * 레이어 변환기
 * - gzip이면 gunzip해서 비압축 tar 생성
 * - diff_id(비압축 sha256) 계산
 */
public final class LayerTransformer {

    private LayerTransformer() {
    }

    /**
     * blob 파일을 비압축 tar로 변환하고 diff_id를 반환
     * @return "sha256:<hex>"
     */
    public static String toUncompressedTarAndDiffId(Path blobFile, Path outTarFile) {
        if (blobFile == null) throw new IllegalArgumentException("blobFile null");
        if (outTarFile == null) throw new IllegalArgumentException("outTarFile null");

        mkdirs(outTarFile.getParent());

        Path tmp = Path.of(outTarFile + ".part");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            try (InputStream fis = Files.newInputStream(blobFile, StandardOpenOption.READ);
                 InputStream in = wrapMaybeGunzip(fis);
                 OutputStream os = Files.newOutputStream(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (n == 0) continue;
                    os.write(buf, 0, n);
                    md.update(buf, 0, n);
                }
                os.flush();
            }

            moveCommit(tmp, outTarFile);

            String hex = toHex(md.digest());
            return "sha256:" + hex;

        } catch (Exception e) {
            safeDelete(tmp);
            throw new IllegalStateException("레이어 변환 실패: " + e.getMessage(), e);
        }
    }

    // gzip 여부 판단 후 래핑
    private static InputStream wrapMaybeGunzip(InputStream raw) throws IOException {
        BufferedInputStream bis = new BufferedInputStream(raw);
        bis.mark(2);
        int b1 = bis.read();
        int b2 = bis.read();
        bis.reset();

        // gzip 매직(1F 8B)
        boolean isGzip = (b1 == 0x1f && b2 == 0x8b);
        if (isGzip) return new GZIPInputStream(bis, 64 * 1024);
        return bis;
    }

    private static void mkdirs(Path dir) {
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("디렉토리 생성 실패: " + dir + ", err=" + e.getMessage(), e);
        }
    }

    private static void moveCommit(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFail) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void safeDelete(Path p) {
        try {
            if (p != null) Files.deleteIfExists(p);
        } catch (Exception ignored) {
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            int v = x & 0xff;
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }
}
