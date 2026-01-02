package dev.rex.demo.infra.tar;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 최소 TAR writer
 * - USTAR 헤더
 * - 파일 크기 선행 필요
 */
public final class TarStreamWriter implements Closeable {

    private static final int RECORD_SIZE = 512;
    private final OutputStream out;
    private boolean finished;

    public TarStreamWriter(OutputStream out) {
        this.out = new BufferedOutputStream(out, 64 * 1024);
    }

    // 바이트 엔트리 작성
    public void putBytes(String name, byte[] bytes) throws IOException {
        if (bytes == null) bytes = new byte[0];
        writeHeader(name, bytes.length, (int) (Instant.now().getEpochSecond()), (byte) '0');
        out.write(bytes);
        pad(bytes.length);
    }

    // 파일 엔트리 작성
    public void putFile(String name, Path file) throws IOException {
        long size = Files.size(file);
        if (size > Integer.MAX_VALUE) {
            // tar는 64bit size도 가능하지만 구현 단순화
            throw new IOException("파일이 너무 큽니다: " + size);
        }
        writeHeader(name, (int) size, (int) (Instant.now().getEpochSecond()), (byte) '0');
        try (InputStream is = Files.newInputStream(file)) {
            copy(is, out);
        }
        pad((int) size);
    }

    // 마무리
    public void finish() throws IOException {
        if (finished) return;
        finished = true;
        // EOF 블록 2개
        out.write(new byte[RECORD_SIZE]);
        out.write(new byte[RECORD_SIZE]);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        finish();
    }

    private void writeHeader(String name, int size, int mtime, byte typeFlag) throws IOException {
        byte[] hdr = new byte[RECORD_SIZE];

        String nm = normalizeName(name);
        byte[] nameBytes = nm.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 100) {
            // 단순 구현: 긴 경로는 실패
            throw new IOException("tar entry name too long: " + nm);
        }
        System.arraycopy(nameBytes, 0, hdr, 0, nameBytes.length);

        // mode
        writeOctal(hdr, 100, 8, 0644);
        // uid/gid
        writeOctal(hdr, 108, 8, 0);
        writeOctal(hdr, 116, 8, 0);
        // size/mtime
        writeOctal(hdr, 124, 12, size);
        writeOctal(hdr, 136, 12, mtime);

        // checksum 자리 공백
        for (int i = 148; i < 156; i++) hdr[i] = (byte) ' ';

        // typeflag
        hdr[156] = typeFlag;

        // magic/version(ustar)
        writeString(hdr, 257, 6, "ustar");
        writeString(hdr, 263, 2, "00");

        // checksum 계산
        int sum = 0;
        for (byte b : hdr) sum += (b & 0xff);
        writeOctal(hdr, 148, 8, sum);

        out.write(hdr);
    }

    private void writeString(byte[] buf, int off, int len, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        int n = Math.min(len, b.length);
        System.arraycopy(b, 0, buf, off, n);
        if (n < len) buf[off + n] = 0;
    }

    private void writeOctal(byte[] buf, int off, int len, int v) {
        String s = Integer.toOctalString(v);
        // 마지막 null + 공백 고려
        int max = len - 1;
        int start = max - s.length();
        for (int i = 0; i < len; i++) buf[off + i] = 0;
        for (int i = 0; i < start; i++) buf[off + i] = (byte) '0';
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, buf, off + start, b.length);
        buf[off + max] = 0;
    }

    private void pad(int size) throws IOException {
        int mod = size % RECORD_SIZE;
        if (mod == 0) return;
        int pad = RECORD_SIZE - mod;
        out.write(new byte[pad]);
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) continue;
            out.write(buf, 0, n);
        }
    }

    private String normalizeName(String name) {
        String n = (name == null) ? "" : name;
        n = n.replace("\\", "/");
        if (n.startsWith("/")) n = n.substring(1);
        return n;
    }
}
