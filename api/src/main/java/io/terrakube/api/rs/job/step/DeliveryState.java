package io.terrakube.api.rs.job.step;

public enum DeliveryState {
    ACKED,
    SPOOLED,
    RECOVERED,
    EXPIRED,
    NOT_DURABLE
}
