package io.terrakube.api.rs.globalvar;

import com.yahoo.elide.annotation.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.parameters.Category;

import jakarta.persistence.*;

import java.sql.Types;
import java.util.UUID;

@CreatePermission(expression = "user is a superuser")
@UpdatePermission(expression = "user is a superuser")
@DeletePermission(expression = "user is a superuser")
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "globalvar")
public class Globalvar {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name="variable_description")
    private String description;

    @UpdatePermission(expression = "admin update secret")
    @Column(name="sensitive")
    private boolean sensitive;

    @Column(name="hcl")
    private boolean hcl;

    @Column(name="variable_key")
    private String key;

    @ReadPermission(expression = "admin read secret")
    @Column(name="variable_value")
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(name="variable_category")
    private Category category;

    @ManyToOne
    private Organization organization;
}
