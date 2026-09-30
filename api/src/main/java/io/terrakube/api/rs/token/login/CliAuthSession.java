package io.terrakube.api.rs.token.login;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.Date;
import java.util.UUID;

@NoArgsConstructor
@Getter
@Setter
@Entity
@Table(name = "cli_auth_session")
public class CliAuthSession extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CliAuthSessionStatus status;

    @Column(name = "cli_redirect_uri", nullable = false)
    private String cliRedirectUri;

    @Column(name = "cli_code_challenge", nullable = false)
    private String cliCodeChallenge;

    @Column(name = "cli_state", nullable = false)
    private String cliState;

    @Column(name = "dex_code_verifier")
    private String dexCodeVerifier;

    @Column(name = "identity_email")
    private String identityEmail;

    @Column(name = "identity_name")
    private String identityName;

    @Column(name = "identity_groups", columnDefinition = "text")
    private String identityGroups;

    @Column(name = "chosen_days")
    private Integer chosenDays;

    @Column(name = "chosen_name")
    private String chosenName;

    @Column(name = "auth_code_hash")
    private String authCodeHash;

    @Column(name = "code_expires_at")
    private Date codeExpiresAt;

    @Column(name = "expires_at", nullable = false)
    private Date expiresAt;
}
