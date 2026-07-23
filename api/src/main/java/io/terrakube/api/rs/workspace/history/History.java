package io.terrakube.api.rs.workspace.history;

import com.yahoo.elide.annotation.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.IdConverter;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.archive.Archive;

import jakarta.persistence.*;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

@Include(rootLevel = false)
@CreatePermission(expression = "user is a super service")
@UpdatePermission(expression = "user is a super service")
@DeletePermission(expression = "user is a super service")
@Paginate(defaultPageSize = 100, maxPageSize = 1000)
@Getter
@Setter
@Entity(name = "history")
public class History extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_reference")
    private String jobReference;

    @Column(name = "output")
    private String output;

    @Column(name = "serial")
    private int serial = 1;

    @Column(name = "md5")
    private String md5 = "0";

    @Column(name = "lineage")
    private String lineage = "0";

    @ManyToOne
    private Workspace workspace;

    @Exclude
    @OneToMany(mappedBy = "history")
    private List<Archive> archive;
}
