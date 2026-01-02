package dev.rex.demo.domain.manifest;

import java.util.List;

public record ParsedManifest(
        boolean manifestList,
        List<String> layerDigests,
        String configDigest
) {
    public boolean isManifestList() {
        return manifestList;
    }
}
