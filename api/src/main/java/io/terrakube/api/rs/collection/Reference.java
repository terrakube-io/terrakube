package io.terrakube.api.rs.collection;

import com.yahoo.elide.annotation.Include;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.workspace.Workspace;

import java.sql.Types;
import java.util.UUID;

@Include
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

    @ManyToOne
    private Collection collection;

    @ManyToOne
    private Workspace workspace;
}
