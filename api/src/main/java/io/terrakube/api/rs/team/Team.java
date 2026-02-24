package io.terrakube.api.rs.team;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.IdConverter;
import io.terrakube.api.rs.Organization;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.UpdatePermission;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

@CreatePermission(expression = "user is a superuser")
@UpdatePermission(expression = "user is a superuser")
@DeletePermission(expression = "user is a superuser")
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "team")
public class Team {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "manage_state")
    private boolean manageState;

    @Column(name = "manage_collection")
    private boolean manageCollection;

    @Column(name = "manage_job")
    private boolean manageJob;

    @Column(name = "manage_workspace")
    private boolean manageWorkspace;

    @Column(name = "manage_module")
    private boolean manageModule;

    @Column(name = "manage_provider")
    private boolean manageProvider;

    @Column(name = "manage_vcs")
    private boolean manageVcs;

    @Column(name = "manage_template")
    private boolean manageTemplate;

    @Column(name = "plan_job")
    private boolean planJob;

    @Column(name = "approve_job")
    private boolean approveJob;

    @Column(name = "role")
    private String role;

    @ManyToOne
    private Organization organization;
}
