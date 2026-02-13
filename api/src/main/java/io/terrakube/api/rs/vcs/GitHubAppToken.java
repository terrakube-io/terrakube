package io.terrakube.api.rs.vcs;

import java.sql.Types;

import java.util.Date;
import java.util.UUID;

import com.yahoo.elide.annotation.Exclude;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.IdConverter;

import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;

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

    @Exclude
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "token_expiration")
    private Date tokenExpiration;
}
