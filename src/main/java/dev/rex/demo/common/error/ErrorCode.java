package dev.rex.demo.common.error;

public enum ErrorCode {

    // ======================
    // Scan
    // ======================
    SCAN_TIMED_OUT_OR_LIMITED,
    NO_IMAGES_IN_REPOSITORY,
    TAGGED_EXISTS_BUT_PUSHED_AT_UNKNOWN,
    LATEST_IMAGE_HAS_NO_TAGS,
    REPOSITORY_NOT_FOUND,

    // ======================
    // Download - Validation
    // ======================
    REPO_NOT_PULLABLE,
    TAG_NOT_LATEST,

    // ======================
    // Input/Validation
    // ======================
    INVALID_REQUEST,

    // ======================
    // Download - Auth
    // ======================
    DOWNLOAD_AUTH_TOKEN_FAILED,
    DOWNLOAD_AUTH_TOKEN_EMPTY,
    DOWNLOAD_UNAUTHORIZED,

    // ======================
    // Download - ECR
    // ======================
    DOWNLOAD_ECR_REPOSITORY_NOT_FOUND,
    DOWNLOAD_ECR_BLOB_NOT_FOUND,
    DOWNLOAD_ECR_THROTTLED,
    DOWNLOAD_ECR_API_FAILED,

    // ======================
    // Download - HTTP/Network
    // ======================
    DOWNLOAD_HTTP_TIMEOUT,
    DOWNLOAD_HTTP_CONNECTION_FAILED,
    DOWNLOAD_HTTP_UNEXPECTED_STATUS,

    // ======================
    // Download - Integrity
    // ======================
    DOWNLOAD_DIGEST_MISMATCH,
    DOWNLOAD_CORRUPTED_CONTENT,

    // ======================
    // Download - Filesystem
    // ======================
    DOWNLOAD_FS_WRITE_FAILED,
    DOWNLOAD_FS_NO_SPACE,
    DOWNLOAD_FS_PERMISSION_DENIED,

    // ======================
    // Download - Execution
    // ======================
    DOWNLOAD_THREAD_INTERRUPTED,
    DOWNLOAD_TASK_FAILED,
    DOWNLOAD_PARTIAL_SUCCESS,

    // ======================
    // Generic
    // ======================
    INTERNAL_ERROR
}
