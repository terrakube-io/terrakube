package io.terrakube.api.rs.module;

import java.sql.Types;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.UpdatePermission;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity(name = "module_version")
@Include(rootLevel = false)
@CreatePermission(expression = "team manage module version")
@UpdatePermission(expression = "team manage module version")
@DeletePermission(expression = "team manage module version")
public class ModuleVersion {
    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @ManyToOne
    private Module module;
    
    @Column(name = "version")
    private String version;
    
    @Column(name = "commit_info")
    private String commit;

    @Column(name = "git_tag")
    private String gitTag;

    @Column(name = "deprecated")
    private boolean deprecated;

    // Removed versions are hidden from the registry but kept so the refresh job does not re-import them.
    @Column(name = "removed")
    private boolean removed;

    // Shown for deprecated and removed versions, e.g. a removal date or upgrade instructions.
    @Column(name = "deprecation_message")
    private String deprecationMessage;
}
