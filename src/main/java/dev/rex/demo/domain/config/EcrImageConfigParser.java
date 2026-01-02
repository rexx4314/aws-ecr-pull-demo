package dev.rex.demo.domain.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * config JSON 파서
 * - rootfs.diff_ids 추출
 */
public final class EcrImageConfigParser {

    private static final ObjectMapper om = new ObjectMapper();

    private EcrImageConfigParser() {
    }

    public static EcrImageConfig parse(byte[] configJsonBytes) {
        if (configJsonBytes == null || configJsonBytes.length == 0) {
            throw new IllegalArgumentException("configJsonBytes is empty");
        }

        try {
            JsonNode root = om.readTree(configJsonBytes);

            // created
            String created = null;
            JsonNode c = root.get("created");
            if (c != null && c.isTextual()) created = StringUtils.trimToNull(c.asText());

            // rootfs.diff_ids
            List<String> diffIds = new ArrayList<>();
            JsonNode rootfs = root.get("rootfs");
            if (rootfs != null && rootfs.isObject()) {
                JsonNode diffs = rootfs.get("diff_ids");
                if (diffs != null && diffs.isArray()) {
                    for (JsonNode x : diffs) {
                        if (x != null && x.isTextual()) {
                            String v = StringUtils.trimToNull(x.asText());
                            if (v != null) diffIds.add(v);
                        }
                    }
                }
            }

            return new EcrImageConfig(List.copyOf(diffIds), created);

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("config 파싱 실패: " + e.getMessage(), e);
        }
    }
}
