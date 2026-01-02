package dev.rex.demo.domain.image;

import java.nio.file.Path;

/**
 * export용 레이어 준비 결과
 */
public record PreparedLayer(
        int index,
        String layerIdHex,
        String chainIdSha256,   // "sha256:<hex>"
        Path layerTarPath,
        String diffIdSha256     // "sha256:<hex>"
) {
}
