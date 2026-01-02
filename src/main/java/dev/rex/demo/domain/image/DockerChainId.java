package dev.rex.demo.domain.image;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Docker chainID 계산기
 * - parentChainId + " " + diffId 를 sha256
 */
public final class DockerChainId {

    private DockerChainId() {
    }

    /**
     * @param parentChainIdSha256 parent chainId ("sha256:<hex>") 또는 null
     * @param diffIdSha256        diff_id ("sha256:<hex>")
     * @return layerId hex (접두어 없는 hex)
     */
    public static String chainIdHex(String parentChainIdSha256, String diffIdSha256) {
        String diff = normalizeSha256(diffIdSha256);
        if (diff == null) throw new IllegalArgumentException("diffIdSha256 null");

        // 첫 레이어는 diff_id 자체가 chainID
        String parent = normalizeSha256(parentChainIdSha256);
        if (parent == null) {
            return stripPrefix(diff);
        }

        String s = parent + " " + diff;
        return sha256Hex(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(bytes);
            return toHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException("chainId sha256 실패: " + e.getMessage(), e);
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

    private static String normalizeSha256(String s) {
        String t = StringUtils.trimToNull(s);
        if (t == null) return null;
        return t.startsWith("sha256:") ? t : ("sha256:" + t);
    }

    private static String stripPrefix(String sha256) {
        if (sha256 == null) return null;
        return sha256.startsWith("sha256:") ? sha256.substring("sha256:".length()) : sha256;
    }
}
