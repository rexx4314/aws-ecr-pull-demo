package dev.rex.demo.ecr.service;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.BatchGetImageRequest;
import software.amazon.awssdk.services.ecr.model.BatchGetImageResponse;
import software.amazon.awssdk.services.ecr.model.Image;
import software.amazon.awssdk.services.ecr.model.ImageIdentifier;

import java.util.List;

/**
 * manifest 조회
 * - BatchGetImage 호출
 * - manifest 존재 검증
 */
@Component
public class ManifestFetcher {

    public String fetchManifestJson(
            EcrClient ecr,
            String registryId,
            String repositoryName,
            ImageRefResolver.ResolvedImageRef ref
    ) {
        ImageIdentifier id = ref.isDigest()
                ? ImageIdentifier.builder().imageDigest(ref.requestedDigest()).build()
                : ImageIdentifier.builder().imageTag(ref.resolvedTag()).build();

        BatchGetImageResponse bg = ecr.batchGetImage(BatchGetImageRequest.builder()
                .registryId(registryId)
                .repositoryName(repositoryName)
                .imageIds(id)
                .build());

        List<Image> images = bg.images();
        if (images == null || images.isEmpty() || images.get(0).imageManifest() == null) {
            throw new ApiException(ErrorCode.DOWNLOAD_FAILED,
                    "BatchGetImage 결과에 imageManifest가 없습니다. tag/digest가 유효한지 확인하세요.");
        }

        return images.get(0).imageManifest();
    }
}
