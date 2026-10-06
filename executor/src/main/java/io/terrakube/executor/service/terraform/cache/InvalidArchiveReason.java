package io.terrakube.executor.service.terraform.cache;

/** Why a local Terraform/OpenTofu release archive (or extracted binary) was rejected. */
public enum InvalidArchiveReason {
    ZERO_BYTE("zero_byte"),
    NOT_REGULAR_FILE("not_regular_file"),
    NOT_ZIP("not_zip"),
    MISSING_EXECUTABLE("missing_executable"),
    INCOMPLETE_BINARY("incomplete_binary");

    private final String metricValue;

    InvalidArchiveReason(String metricValue) {
        this.metricValue = metricValue;
    }

    /** The {@code reason} tag value used on {@code terrakube.executor.binary.cache.invalid}. */
    public String metricValue() {
        return metricValue;
    }
}
