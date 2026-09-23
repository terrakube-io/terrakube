package io.terrakube.api.rs.token.login;

public enum CliAuthSessionStatus {
    PENDING_IDP, PENDING_CONSENT, CODE_ISSUED, EXCHANGED, DENIED, FAILED
}
