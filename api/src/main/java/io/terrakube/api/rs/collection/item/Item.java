package io.terrakube.api.rs.collection.item;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.workspace.parameters.Category;

import java.sql.Types;
import java.util.UUID;

@Include(rootLevel = false)
@ReadPermission(expression = "team view collection item")
@CreatePermission(expression = "team manage collection item")
@UpdatePermission(expression = "team manage collection item")
@DeletePermission(expression = "team manage collection item")
@Getter
@Setter
@Entity
public class Item {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name="item_key")
    private String key;

    @ReadPermission(expression = "user read collection")
    @Column(name="item_value")
    private String value;

    @Column(name="item_description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name="item_category")
    private Category category;

    @UpdatePermission(expression = "team manage collection item AND user update collection secret")
    @Column(name="sensitive")
    private boolean sensitive;

    @Column(name="hcl")
    private boolean hcl;

    @ManyToOne
    private Collection collection;
}
