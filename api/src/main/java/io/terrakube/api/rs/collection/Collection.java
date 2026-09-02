package io.terrakube.api.rs.collection;

import com.yahoo.elide.annotation.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.collection.item.Item;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

@ReadPermission(expression = "team view collection")
@CreatePermission(expression = "team manage collection")
@UpdatePermission(expression = "team manage collection")
@DeletePermission(expression = "team manage collection")
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "collection")
public class Collection extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "priority")
    private int priority;

    @ManyToOne
    private Organization organization;

    @OneToMany(mappedBy = "collection")
    List<Item> item;

    @OneToMany(mappedBy = "collection")
    List<Reference> reference;
}
