package io.terrakube.api.rs.notification;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.plugin.security.audit.GenericAuditFields;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.template.Template;
import io.terrakube.api.rs.workspace.Workspace;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

@Include
@Getter
@Setter
@ReadPermission(expression = "team read notification configuration")
@CreatePermission(expression = "team manage notification configuration")
@UpdatePermission(expression = "team manage notification configuration")
@DeletePermission(expression = "team manage notification configuration")
@Entity(name = "notification_configuration")
public class NotificationConfiguration extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;

    // Plain @Column (not @Lob) mapped to a "text" column - see NotificationOutbox for why @Lob
    // is avoided here: it maps to PostgreSQL's oid-backed Large Object storage, which requires
    // every read to happen inside a real (non-autocommit) transaction.
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Organization organization;

    // EAGER, not LAZY: reading configuration.getWorkspace().getId() through a plain Java getter
    // (e.g. from TeamManageNotificationConfiguration's permission check) returns the correct id
    // off an uninitialized Hibernate proxy just fine, but Elide's GraphQL serialization of the
    // nested "workspace { edges { node { id } } }" relationship does not go through that getter -
    // it reads the proxy's own backing id field directly, which is unset until the proxy is
    // triggered, and renders as the literal string "null" instead of the real UUID or JSON null.
    // Eager fetch means there's a fully-populated Workspace object by serialization time, not a
    // lazy proxy, so there's no uninitialized field for that path to read.
    @ManyToOne(fetch = FetchType.EAGER)
    private Workspace workspace;

    @Column(name = "channel_type")
    @Enumerated(EnumType.STRING)
    private NotificationChannelType channelType;

    @Column(name = "destination_url")
    private String destinationUrl;

    @ReadPermission(expression = "read notification signing secret")
    @Column(name = "signing_secret")
    private String signingSecret;

    private boolean active = true;

    // DETAILED renders the full card (org/job/commit, View Run button, sent-by footer) for every
    // status; SIMPLE renders a single-line ping instead. Defaults to DETAILED - not tied to job
    // status, so the same choice applies uniformly across the run's lifecycle rather than this
    // code silently deciding which statuses deserve detail.
    @Column(name = "message_style")
    @Enumerated(EnumType.STRING)
    private NotificationMessageStyle messageStyle = NotificationMessageStyle.DETAILED;

    @OneToMany(mappedBy = "configuration", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE)
    private List<NotificationTrigger> triggers;

    // Empty/null means "applies to every template" - this only ever narrows which templates a
    // configuration fires for (see JobNotificationTrigger), it never widens it.
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "notification_configuration_template",
            joinColumns = @JoinColumn(name = "configuration_id"),
            inverseJoinColumns = @JoinColumn(name = "template_id"))
    private List<Template> templates;

    // Elide's nested-URL relationship inference only populates the immediate parent
    // (workspace, for organization/{orgId}/workspace/{wsId}/notificationConfiguration)
    // - it doesn't also walk up to set organization, even though the column is
    // NOT NULL. Derive it from the workspace whenever the client didn't set it
    // directly (the org-scoped create path, organization/{orgId}/notificationConfiguration,
    // already sets it from its own immediate parent).
    @PrePersist
    @PreUpdate
    private void syncOrganizationFromWorkspace() {
        if (organization == null && workspace != null) {
            organization = workspace.getOrganization();
        }
    }
}
