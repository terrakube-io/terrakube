package io.terrakube.api.rs.workspace.parameters;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.sql.Types;
import java.util.UUID;

@Include(rootLevel = false)
@ReadPermission(expression = "team view variable OR user is an executor service")
@CreatePermission(expression = "team manage variable")
@UpdatePermission(expression = "team manage variable")
@DeletePermission(expression = "team manage variable")
@Getter
@Setter
@Entity(name = "variable")
public class Variable {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name="variable_key")
    private String key;

    @ReadPermission(expression = "user read secret")
    @Column(name="variable_value")
    private String value;

    @Column(name="variable_description")
    private String description;

    @NotNull(message = "category is required and must be TERRAFORM or ENV")
    @Enumerated(EnumType.STRING)
    @Column(name="variable_category", nullable = false)
    private Category category;

    @Column(name="sensitive")
    private boolean sensitive;

    @Column(name="hcl")
    private boolean hcl;

    @Column(name="incomplete")
    private boolean incomplete;

    @ManyToOne
    private Workspace workspace;

    @PrePersist
    @PreUpdate
    private void syncIncompleteFlag() {
        incomplete = sensitive && (value == null || value.isBlank());
    }
}

