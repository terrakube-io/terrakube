package io.terrakube.api.rs.provider.implementation;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.UpdatePermission;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.DeprecationMessageConverter;
import io.terrakube.api.rs.VersionStatus;
import io.terrakube.api.rs.provider.Provider;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private VersionStatus status = VersionStatus.active;

    // Shown for deprecated and removed versions, e.g. a removal date or upgrade instructions.
    @Size(max = 1024)
    @Convert(converter = DeprecationMessageConverter.class)
    @Column(name = "deprecation_message")
    private String deprecationMessage;

    @ManyToOne
    private Provider provider;

    @OneToMany(mappedBy = "version")
    List<Implementation> implementation;

}
