package io.terrakube.api.rs.vcs;

import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.IdConverter;

import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity(name = "github_app_token")
@Include(rootLevel = true)
public class GitHubAppToken extends GenericAuditFields {
    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner")
    private String owner;

    @Column(name = "installation_id")
    private String installationId;

    @ReadPermission(expression = "read github app installation token")
    @Column(name = "token")
    private String token;

    @Column(name = "app_id")
    private String appId;

    @Column(name = "expires_at")
    private Instant expiresAt;
}
