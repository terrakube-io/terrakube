package io.terrakube.api.rs.template;

import com.yahoo.elide.annotation.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;

import jakarta.persistence.*;

import java.sql.Types;
import java.util.UUID;

@ReadPermission(expression = "team view template")
@CreatePermission(expression = "team manage template")
@UpdatePermission(expression = "team manage template")
@DeletePermission(expression = "team manage template")
@Include
@Getter
@Setter
@Entity(name = "template")
public class Template extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "version")
    private String version;

    @Column(name = "tcl")
    private String tcl;

    @ManyToOne
    private Organization organization;
}
