package dev.rex.demo.ecr.service;

import dev.rex.demo.common.error.ApiException;
import dev.rex.demo.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.*;

import java.time.Instant;
import java.util.List;

/**
 * 이미지 레퍼런스 결정
 * - resolveLatest/tag/digest 처리
 * - 최신 tag 스캔
 * - digest 보정
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageRefResolver {

    // describeImages 페이지 크기
    private static final int DESCRIBE_IMAGES_PAGE_SIZE = 100;

    public ResolvedImageRef resolve(
            EcrClient ecr,
            String registryId,
            String repositoryName,
            boolean resolveLatest,
            String tag,
            String digest,
            int maxPages,
            int maxImages
    ) {
        if (resolveLatest) {
            LatestSelection sel = resolveLatestTag(ecr, registryId, repositoryName, maxPages, maxImages);
            if (sel.tag() == null) {
                throw new ApiException(ErrorCode.NO_IMAGES_IN_REPOSITORY,
                        "resolveLatest=true 이지만 TAGGED 이미지가 없습니다(repo empty 가능).");
            }
            return ResolvedImageRef.byTag(sel.tag());
        }

        String t = StringUtils.trimToNull(tag);
        String d = StringUtils.trimToNull(digest);

        // 교차 검증은 DTO에서 보장(@AssertTrue)
        if (d != null) return ResolvedImageRef.byDigest(d);
        return ResolvedImageRef.byTag(t);
    }

    public String resolveDigestIfNeeded(EcrClient ecr, String registryId, String repositoryName, ResolvedImageRef ref) {
        if (ref.isDigest()) return ref.requestedDigest();

        String tag = ref.resolvedTag();
        if (tag == null) return null;

        return tryResolveDigestByTag(ecr, registryId, repositoryName, tag);
    }

    // 최신 tag 탐색
    private LatestSelection resolveLatestTag(EcrClient ecr, String registryId, String repositoryName, int maxPages, int maxImages) {
        String next = null;
        int pages = 0;
        int scanned = 0;

        Instant bestPushedAt = null;
        String bestTag = null;

        do {
            if (++pages > maxPages) {
                log.warn("resolveLatest: reached maxPages. repo={}, maxPages={}", repositoryName, maxPages);
                break;
            }

            DescribeImagesResponse r = ecr.describeImages(DescribeImagesRequest.builder()
                    .registryId(registryId)
                    .repositoryName(repositoryName)
                    .filter(DescribeImagesFilter.builder().tagStatus(TagStatus.TAGGED).build())
                    .nextToken(next)
                    .maxResults(DESCRIBE_IMAGES_PAGE_SIZE)
                    .build());

            List<ImageDetail> details = r.imageDetails();
            if (details != null && !details.isEmpty()) {
                for (ImageDetail d : details) {
                    scanned++;
                    if (scanned > maxImages) {
                        log.warn("resolveLatest: reached maxImages. repo={}, maxImages={}", repositoryName, maxImages);
                        return new LatestSelection(bestTag, bestPushedAt);
                    }

                    Instant pushedAt = d.imagePushedAt();
                    List<String> tags = d.imageTags();
                    if (pushedAt == null || tags == null || tags.isEmpty()) continue;

                    if (bestPushedAt == null || pushedAt.isAfter(bestPushedAt)) {
                        bestPushedAt = pushedAt;
                        bestTag = tags.get(0);
                    }
                }
            }

            next = normalizeToken(r.nextToken());
        } while (next != null);

        return new LatestSelection(bestTag, bestPushedAt);
    }

    // tag -> digest 조회
    private String tryResolveDigestByTag(EcrClient ecr, String registryId, String repositoryName, String tag) {
        try {
            DescribeImagesResponse r = ecr.describeImages(DescribeImagesRequest.builder()
                    .registryId(registryId)
                    .repositoryName(repositoryName)
                    .imageIds(ImageIdentifier.builder().imageTag(tag).build())
                    .build());

            if (r.imageDetails() == null || r.imageDetails().isEmpty()) return null;
            return r.imageDetails().get(0).imageDigest();

        } catch (Exception ex) {
            log.warn("resolve digest by tag failed. repo={}, tag={}, err={}", repositoryName, tag, ex.getMessage());
            return null;
        }
    }

    private String normalizeToken(String token) {
        return StringUtils.trimToNull(token);
    }

    private record LatestSelection(String tag, Instant pushedAt) {
    }

    // 이미지 레퍼런스
    public record ResolvedImageRef(boolean isDigest, String resolvedTag, String requestedDigest) {
        public static ResolvedImageRef byTag(String tag) {
            return new ResolvedImageRef(false, StringUtils.trimToNull(tag), null);
        }

        public static ResolvedImageRef byDigest(String digest) {
            return new ResolvedImageRef(true, null, StringUtils.trimToNull(digest));
        }
    }
}
