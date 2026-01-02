package dev.rex.demo.infra.tar;

import org.apache.commons.lang3.StringUtils;

public final class DockerSaveNames {

    private DockerSaveNames() {
    }

    public static String stripSha256Prefix(String digest) {
        String d = StringUtils.trimToNull(digest);
        if (d == null) return null;
        return d.startsWith("sha256:") ? d.substring("sha256:".length()) : d;
    }

    public static String safeTarFileName(String hint) {
        String s = StringUtils.trimToNull(hint);
        if (s == null) return "docker-save.tar";

        s = s.replace("\\", "_").replace("/", "_");
        s = s.replace("..", "_");
        s = s.replace(":", "_");

        if (!s.toLowerCase().endsWith(".tar")) s = s + ".tar";
        return s;
    }
}
