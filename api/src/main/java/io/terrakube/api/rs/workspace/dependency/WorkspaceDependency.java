package io.terrakube.api.rs.workspace.dependency;

import com.yahoo.elide.annotation.Include;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Declares that {@code workspace} consumes output from {@code dependsOn}, so that a
 * successful apply on the producer can trigger a run on the consumer.
 *
 * Both sides are workspaces of the same organization; the relationship is directed and
 * a workspace may declare several dependencies.
 */
@Include(rootLevel = false, name = "dependency")
@Getter
@Setter
@Entity
@Table(name = "workspace_dependency")
public class WorkspaceDependency {

    @Id
    private UUID id;

    /** The workspace that should run after its dependency applies. */
    @ManyToOne
    @JoinColumn(name = "workspace_id")
    private Workspace workspace;

    /** The workspace whose successful apply triggers the consumer. */
    @ManyToOne
    @JoinColumn(name = "depends_on_workspace_id")
    private Workspace dependsOn;

    /**
     * Template used for the triggered run. When null the consumer's default template is
     * used, which keeps the common case free of configuration.
     */
    @Column(name = "template_reference")
    private String templateReference;
}
