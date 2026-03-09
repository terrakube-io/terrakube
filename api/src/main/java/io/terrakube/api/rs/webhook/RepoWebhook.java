package io.terrakube.api.rs.webhook;


import io.terrakube.api.rs.IdConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "repo_webhook")
public class RepoWebhook {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repository_url", length = 1024, nullable = false)
    private String repositoryUrl;

    @Column(name = "remote_hook_id")
    private String remoteHookId;

    @Column(name = "secret", length = 128)
    private String secret;
}
