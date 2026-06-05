package io.terrakube.api.rs.vcs;

import java.sql.Types;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.yahoo.elide.annotation.*;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.IdConverter;
import io.terrakube.api.rs.Organization;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;
import lombok.Getter;
import lombok.Setter;
import io.terrakube.api.rs.workspace.Workspace;

@ReadPermission(expression = "team view vcs")
@CreatePermission(expression = "team manage vcs")
@UpdatePermission(expression = "team manage vcs")
@DeletePermission(expression = "team manage vcs")
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "vcs")
public class Vcs extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "vcs_type")
    private VcsType vcsType;

    @Column(name = "description")
    private String description;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "callback")
    private String callback;

    @Column(name = "endpoint")
    private String endpoint;

    @Column(name = "api_url")
    private String apiUrl;

    @ReadPermission(expression = "read vcs secret")
    @Column(name = "client_secret")
    private String clientSecret;

    @ReadPermission(expression = "read vcs secret")
    @Column(name = "private_key")
    private String privateKey;

    @Column(name = "connection_type")
    @Enumerated(EnumType.STRING)
    private VcsConnectionType connectionType = VcsConnectionType.OAUTH;
   
    @UpdatePermission(expression = "user is a super service")
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private VcsStatus status = VcsStatus.PENDING;

    @ReadPermission(expression = "read access token")
    @Column(name = "access_token")
    private String accessToken;

    @Exclude
    @Column(name = "refresh_token")
    private String refreshToken;

    @Exclude
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "token_expiration")
    private Date tokenExpiration;

    @Column(name = "redirect_url")
    private String redirectUrl;

    @ManyToOne
    private Organization organization;

    @OneToMany(mappedBy = "vcs")
    private List<Workspace> workspace;
}
