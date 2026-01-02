package dev.rex.demo.ecr.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Docker/OCI manifest 파서
 * - manifest list(멀티 아키텍처) 감지
 * - layers[].digest 추출
 * - config.digest 추출
 */
public final class ManifestParser {

    private static final ObjectMapper om = new ObjectMapper();

    private ManifestParser() {
    }

    public static ParsedManifest parse(String manifestJson) {
        String s = StringUtils.trimToNull(manifestJson);
        if (s == null) throw new IllegalArgumentException("manifestJson is blank");

        try {
            JsonNode root = om.readTree(s);

            // manifest list 감지
            if (isManifestList(root)) {
                return new ParsedManifest(true, List.of(), null);
            }

            // layer digest 추출
            List<String> layerDigests = extractLayerDigests(root);

            // config digest 추출
            String configDigest = extractConfigDigest(root);

            if (layerDigests.isEmpty()) {
                throw new IllegalStateException("manifest에서 layers[].digest를 찾지 못했습니다(스키마 확인 필요).");
            }

            return new ParsedManifest(false, layerDigests, configDigest);

        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new IllegalStateException("manifest 파싱 실패: " + e.getMessage(), e);
        }
    }

    private static boolean isManifestList(JsonNode root) {
        JsonNode manifests = root.get("manifests");
        return manifests != null && manifests.isArray();
    }

    private static List<String> extractLayerDigests(JsonNode root) {
        List<String> out = new ArrayList<>();
        JsonNode layers = root.get("layers");
        if (layers != null && layers.isArray()) {
            for (JsonNode l : layers) {
                JsonNode d = l.get("digest");
                if (d != null && d.isTextual()) {
                    String digest = StringUtils.trimToNull(d.asText());
                    if (digest != null) out.add(digest);
                }
            }
        }
        return out;
    }

    private static String extractConfigDigest(JsonNode root) {
        JsonNode cfg = root.get("config");
        if (cfg != null && cfg.isObject()) {
            JsonNode d = cfg.get("digest");
            if (d != null && d.isTextual()) {
                return StringUtils.trimToNull(d.asText());
            }
        }
        return null;
    }
}
