package io.terrakube.api.rs.tag;

import com.yahoo.elide.annotation.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;

import jakarta.persistence.*;

import java.sql.Types;
import java.util.UUID;

@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "tag")
public class Tag extends GenericAuditFields {
    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @ManyToOne
    private Organization organization;

}
