package dev.rex.demo.ecr.manifest;

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
