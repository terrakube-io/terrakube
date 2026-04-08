package io.terrakube.api.rs.workspace.parameters;

import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.IdConverter;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.*;
import java.sql.Types;
import java.util.UUID;

@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "variable")
public class Variable {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name="variable_key")
    private String key;

    @ReadPermission(expression = "user read secret")
    @Column(name="variable_value")
    private String value;

    @Column(name="variable_description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name="variable_category")
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

