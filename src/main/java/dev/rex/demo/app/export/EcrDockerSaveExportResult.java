package dev.rex.demo.app.export;

import java.nio.file.Path;

public record EcrDockerSaveExportResult(
        Path tarPath,
        Path sourceImageDir
) {
}
