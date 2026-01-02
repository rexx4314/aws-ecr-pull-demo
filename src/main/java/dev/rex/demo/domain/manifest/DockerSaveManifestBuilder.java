package dev.rex.demo.domain.manifest;

import dev.rex.demo.domain.image.PreparedLayer;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * docker save tar 메타 생성기
 */
public final class DockerSaveManifestBuilder {

    private DockerSaveManifestBuilder() {
    }

    // root manifest.json 생성
    public static String buildManifestJson(String imageIdHex, String repoTag, List<PreparedLayer> layers) {
        String cfg = escape(imageIdHex + ".json");
        String rt = escape(repoTag);

        StringBuilder sb = new StringBuilder();
        sb.append("[{\"Config\":\"").append(cfg).append("\",\"RepoTags\":[\"").append(rt).append("\"],\"Layers\":[");
        for (int i = 0; i < layers.size(); i++) {
            PreparedLayer pl = layers.get(i);
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(pl.layerIdHex() + "/layer.tar")).append("\"");
        }
        sb.append("]}]");
        return sb.toString();
    }

    // repositories 생성
    public static String buildRepositoriesJson(String imageIdHex, String repoTag) {
        String repo = repoName(repoTag);
        String tag = tagName(repoTag);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"").append(escape(repo)).append("\":{\"").append(escape(tag)).append("\":\"").append(escape(imageIdHex)).append("\"}}");
        return sb.toString();
    }

    // layer json 생성
    public static String buildLayerJson(String idHex, String parentHex, String createdIso) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":\"").append(escape(idHex)).append("\"");
        if (StringUtils.isNotBlank(parentHex)) {
            sb.append(",\"parent\":\"").append(escape(parentHex)).append("\"");
        }
        if (StringUtils.isNotBlank(createdIso)) {
            sb.append(",\"created\":\"").append(escape(createdIso)).append("\"");
        }
        sb.append(",\"container_config\":{}");
        sb.append("}");
        return sb.toString();
    }

    private static String repoName(String repoTag) {
        String x = StringUtils.trimToEmpty(repoTag);
        int idx = x.lastIndexOf(':');
        if (idx <= 0) return x;
        return x.substring(0, idx);
    }

    private static String tagName(String repoTag) {
        String x = StringUtils.trimToEmpty(repoTag);
        int idx = x.lastIndexOf(':');
        if (idx < 0) return "latest";
        return x.substring(idx + 1);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
