package io.terrakube.api.rs.provider.implementation;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.UpdatePermission;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.provider.Provider;

import jakarta.persistence.*;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

@Include(rootLevel = false)
@CreatePermission(expression = "team manage provider version")
@UpdatePermission(expression = "team manage provider version")
@DeletePermission(expression = "team manage provider version")
@Getter
@Setter
@Entity(name = "version")
public class Version {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "version_number")
    private String versionNumber;

    @Column(name = "protocols")
    private String protocols;

    @Column(name = "deprecated")
    private boolean deprecated;

    // Removed versions are hidden from the registry but kept so the refresh job does not re-import them.
    @Column(name = "removed")
    private boolean removed;

    // Shown for deprecated and removed versions, e.g. a removal date or upgrade instructions.
    @Column(name = "deprecation_message")
    private String deprecationMessage;

    @ManyToOne
    private Provider provider;

    @OneToMany(mappedBy = "version")
    List<Implementation> implementation;

}
