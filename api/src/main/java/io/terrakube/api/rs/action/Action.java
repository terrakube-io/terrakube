package io.terrakube.api.rs.action;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;

import lombok.Getter;
import lombok.Setter;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Include
@Getter
@Setter
@Entity(name = "action")
@ReadPermission(expression = "read action")
@CreatePermission(expression = "user is a superuser")
@UpdatePermission(expression = "user is a superuser")
@DeletePermission(expression = "user is a superuser")
public class Action extends GenericAuditFields {
    @Id
    private String id;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "action", nullable = false, columnDefinition = "TEXT")
    private String action;

    @Column(name = "display_criteria", nullable = false, columnDefinition = "TEXT")
    private String displayCriteria;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "description")
    private String description;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "active", nullable = false)
    private boolean active;
}
