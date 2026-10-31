package io.terrakube.api.rs.job.address;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;

import com.yahoo.elide.annotation.CreatePermission;
import com.yahoo.elide.annotation.DeletePermission;
import com.yahoo.elide.annotation.Exclude;
import com.yahoo.elide.annotation.Include;
import com.yahoo.elide.annotation.ReadPermission;
import com.yahoo.elide.annotation.UpdatePermission;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.job.Job;

import jakarta.persistence.*;

import java.sql.Types;
import java.util.UUID;

@Include
@ReadPermission(expression = "team view job address OR user is an executor service")
@CreatePermission(expression = "team manage job address OR user is a super service")
@UpdatePermission(expression = "team manage job address OR user is a super service")
@DeletePermission(expression = "team manage job address OR user is a super service")
@Getter
@Setter
@Entity(name = "address")
public class Address extends GenericAuditFields {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    private String name;

    @Exclude
    @Column(name = "type")
    @Enumerated(EnumType.STRING)
    private AddressType type;

    @UpdatePermission(expression = "user is a superuser")
    @ManyToOne
    private Job job;
}
