package io.terrakube.api.schema.fixture;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.time.Year;
import java.util.List;
import java.util.Map;

@Include(name = "widget", rootLevel = true)
@ReadPermission(expression = "deny")
@CreatePermission(expression = "deny")
@UpdatePermission(expression = "deny")
@DeletePermission(expression = "deny")
public class Widget extends ExampleBase {

    @Id
    private Long id;

    private int retries;

    private String label = "label";

    @Column(name = "description")
    private String description;

    @ReadPermission(expression = "deny")
    @Column(name = "secret_note", nullable = false)
    private String secret;

    @ReadPermission(expression = "deny")
    @Column(name = "hidden_note")
    private String hiddenNote;

    private Year mysteryDate;

    private List<Year> mysteryDates;

    private ExampleKind kind;

    private Map<String, String> labels;

    private List<String> tags;

    private Object payload;

    @ManyToOne
    private Widget parent;

    @JoinColumn(name = "owner_id", nullable = false)
    @ManyToOne
    private Widget owner;

    @OneToMany(mappedBy = "parent")
    private List<Widget> children;

    @OneToOne
    private Widget detail;
}
