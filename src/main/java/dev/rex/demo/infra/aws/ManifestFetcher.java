package dev.rex.demo.infra.aws;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import dev.rex.demo.domain.image.ImageRefResolver;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.*;

import java.util.List;
import java.util.Map;

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
        try {
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
                throw new ApiException(
                        ErrorCode.DOWNLOAD_ECR_BLOB_NOT_FOUND,
                        "BatchGetImage 결과에 imageManifest가 없습니다. tag/digest가 유효한지 확인하세요.",
                        Map.of(
                                "repositoryName", repositoryName,
                                "refType", ref.isDigest() ? "digest" : "tag",
                                "ref", ref.isDigest() ? safe(ref.requestedDigest()) : safe(ref.resolvedTag())
                        )
                );
            }

            return images.get(0).imageManifest();

        } catch (ApiException ae) {
            throw ae;
        } catch (EcrException ee) {
            ErrorCode code = classifyEcrException(ee);
            throw new ApiException(
                    code,
                    "ECR BatchGetImage 실패: " + safeAwsMsg(ee),
                    Map.of(
                            "status", ee.statusCode(),
                            "repositoryName", repositoryName
                    ),
                    ee
            );
        }
    }

    private ErrorCode classifyEcrException(EcrException e) {
        if (e == null) return ErrorCode.DOWNLOAD_ECR_API_FAILED;

        int sc = e.statusCode();
        String awsCode = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorCode() : null;

        if (sc == 401 || sc == 403) return ErrorCode.DOWNLOAD_UNAUTHORIZED;

        if (sc == 429 || "ThrottlingException".equals(awsCode) || "TooManyRequestsException".equals(awsCode)) {
            return ErrorCode.DOWNLOAD_ECR_THROTTLED;
        }

        if ("RepositoryNotFoundException".equals(awsCode)) return ErrorCode.DOWNLOAD_ECR_REPOSITORY_NOT_FOUND;
        if ("ImageNotFoundException".equals(awsCode)) return ErrorCode.DOWNLOAD_ECR_BLOB_NOT_FOUND;

        if (sc == 404) return ErrorCode.DOWNLOAD_ECR_BLOB_NOT_FOUND;

        return ErrorCode.DOWNLOAD_ECR_API_FAILED;
    }

    private String safeAwsMsg(EcrException e) {
        String m = (e.awsErrorDetails() != null) ? e.awsErrorDetails().errorMessage() : null;
        if (m == null || m.isBlank()) m = e.getMessage();
        return m;
    }

    private String safe(String s) {
        String t = StringUtils.trimToNull(s);
        return (t == null) ? "" : t;
    }
}
