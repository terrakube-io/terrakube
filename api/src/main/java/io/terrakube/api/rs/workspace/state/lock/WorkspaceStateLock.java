package io.terrakube.api.rs.workspace.state.lock;

import io.terrakube.api.rs.IdConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.Date;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "workspace_state_lock")
public class WorkspaceStateLock {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "lock_id", length = 128)
    private String lockId;

    @Column(name = "lock_info", columnDefinition = "TEXT")
    private String lockInfo;

    @Column(name = "acquired_at")
    private Date acquiredAt;
}
