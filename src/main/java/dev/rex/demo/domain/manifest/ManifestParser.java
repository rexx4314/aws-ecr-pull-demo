package dev.rex.demo.domain.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Docker / OCI 단일 이미지 manifest 파서
 *
 * <p>
 * 책임(Responsibility):
 * - manifest JSON 문자열을 파싱하여 ParsedManifest로 변환
 * - manifest list(멀티 아키텍처/index) 여부 판단
 * - layers[].digest 추출
 * - config.digest 추출
 *
 * <p>
 * 정책/제약:
 * - manifestJson이 blank/null이면 즉시 실패 (IllegalArgumentException)
 * - JSON 파싱 실패는 IllegalStateException으로 래핑
 * - manifest list인 경우:
 * - 실제 레이어 정보가 없으므로
 * - ParsedManifest(manifestList=true, empty layers, null config) 반환
 * - 단일 manifest인 경우:
 * - layers[].digest는 반드시 1개 이상 존재해야 함
 * - 없으면 비정상 manifest로 간주하여 예외 발생
 *
 * <p>
 * 주의:
 * - 이 클래스는 "파싱"만 담당
 * - 정책 결정(예: manifest list 허용 여부)은 상위 레이어 책임
 */
public final class ManifestParser {

    /**
     * ObjectMapper는 thread-safe (구성 변경이 없는 경우)
     * → static 재사용하여 비용 절감
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ManifestParser() {
        // util class: 인스턴스화 금지
    }

    /**
     * manifest JSON 문자열을 파싱하여 ParsedManifest로 변환
     *
     * @param manifestJson Docker/OCI manifest(JSON) 문자열
     * @return ParsedManifest 파싱 결과
     * @throws IllegalArgumentException manifestJson이 null/blank인 경우
     * @throws IllegalStateException    JSON 파싱 실패
     *                                  또는 단일 manifest인데 layers[].digest가 없는 경우
     */
    public static ParsedManifest parse(String manifestJson) {
        // 1) 입력값 정규화
        // - 공백/빈 문자열은 허용하지 않음
        String normalized = StringUtils.trimToNull(manifestJson);
        if (normalized == null) {
            throw new IllegalArgumentException("manifestJson is blank");
        }

        try {
            // 2) JSON 문자열 → JsonNode 트리 파싱
            JsonNode root = OBJECT_MAPPER.readTree(normalized);

            // 3) manifest list(멀티 아키텍처 index) 여부 판단
            if (isManifestList(root)) {
                // 상위 레이어에서 정책적으로 거부/선택 처리할 수 있도록
                // 여기서는 상태만 담아 반환
                return new ParsedManifest(true, List.of(), null);
            }

            // 4) 단일 manifest 처리
            // - layers[].digest 추출
            // - config.digest 추출
            List<String> layerDigests = extractLayerDigests(root);
            String configDigest = extractConfigDigest(root);

            // 5) 단일 이미지 manifest에서 layers는 필수
            if (layerDigests.isEmpty()) {
                throw new IllegalStateException(
                        "manifest에서 layers[].digest를 찾지 못했습니다 (스키마 확인 필요)"
                );
            }

            // 6) 정상 파싱 결과 반환
            // - List.copyOf: 외부 변경 방지
            return new ParsedManifest(false, List.copyOf(layerDigests), configDigest);

        } catch (RuntimeException re) {
            // IllegalArgumentException / IllegalStateException 등
            // 의도된 런타임 예외는 그대로 전파
            throw re;

        } catch (Exception e) {
            // checked exception(JSON 파싱 등)은
            // 호출자가 다루기 쉬운 IllegalStateException으로 래핑
            throw new IllegalStateException("manifest 파싱 실패: " + e.getMessage(), e);
        }
    }

    /**
     * manifest list(멀티 아키텍처 index) 여부 판별
     *
     * <p>
     * 일반적인 형태:
     * {
     * "schemaVersion": 2,
     * "manifests": [ ... ]
     * }
     *
     * @param root JSON root node
     * @return manifest list이면 true
     */
    private static boolean isManifestList(JsonNode root) {
        if (root == null) return false;

        JsonNode manifests = root.get("manifests");
        return manifests != null && manifests.isArray();
    }

    /**
     * layers[].digest 추출
     *
     * <p>
     * 기대 구조:
     * - root.layers : array
     * - each layer : object
     * - layer.digest : string
     *
     * @param root JSON root node
     * @return digest 목록 (입력 순서 유지), 없으면 빈 리스트
     */
    private static List<String> extractLayerDigests(JsonNode root) {
        List<String> out = new ArrayList<>();

        JsonNode layers = (root == null) ? null : root.get("layers");
        if (layers == null || !layers.isArray()) {
            // layers가 없는 경우(비정상 또는 다른 스키마)
            return out;
        }

        for (JsonNode layerNode : layers) {
            if (layerNode == null || !layerNode.isObject()) continue;

            JsonNode digestNode = layerNode.get("digest");
            if (digestNode == null || !digestNode.isTextual()) continue;

            String digest = StringUtils.trimToNull(digestNode.asText());
            if (digest != null) {
                out.add(digest);
            }
        }

        return out;
    }

    /**
     * config.digest 추출
     *
     * <p>
     * 기대 구조:
     * - root.config : object
     * - config.digest : string
     *
     * @param root JSON root node
     * @return config digest 또는 null
     */
    private static String extractConfigDigest(JsonNode root) {
        JsonNode cfg = (root == null) ? null : root.get("config");
        if (cfg == null || !cfg.isObject()) {
            return null;
        }

        JsonNode digestNode = cfg.get("digest");
        if (digestNode == null || !digestNode.isTextual()) {
            return null;
        }

        return StringUtils.trimToNull(digestNode.asText());
    }
}
