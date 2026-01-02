package dev.rex.demo.common.error;

public enum ErrorCode {

    // Scan
    SCAN_TIMED_OUT_OR_LIMITED,
    NO_IMAGES_IN_REPOSITORY,
    TAGGED_EXISTS_BUT_PUSHED_AT_UNKNOWN,
    LATEST_IMAGE_HAS_NO_TAGS,
    REPOSITORY_NOT_FOUND,

    // Download validate
    REPO_NOT_PULLABLE,
    TAG_NOT_LATEST,
    REPO_SCAN_CACHE_MISS,

    // Input/Validation
    INVALID_REQUEST,

    // Download/HTTP
    DOWNLOAD_FAILED,

    // Generic
    INTERNAL_ERROR
}
