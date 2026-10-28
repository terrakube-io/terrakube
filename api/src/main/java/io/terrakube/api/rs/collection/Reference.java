package io.terrakube.api.rs.collection;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.workspace.Workspace;

import java.sql.Types;
import java.util.UUID;

@Include
@ReadPermission(expression = "team view collection reference")
@CreatePermission(expression = "team manage collection reference")
@UpdatePermission(expression = "team manage collection reference")
@DeletePermission(expression = "team manage collection reference")
@Getter
@Setter
@Entity(name = "reference")
public class Reference {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "description")
    private String description;

    @UpdatePermission(expression = "user is a superuser")
    @ManyToOne
    private Collection collection;

    @UpdatePermission(expression = "user is a superuser")
    @ManyToOne
    private Workspace workspace;
}
