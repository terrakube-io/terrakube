package io.terrakube.api.rs.job.address;

import io.terrakube.api.plugin.security.audit.GenericAuditFields;

import com.yahoo.elide.annotation.Exclude;
import com.yahoo.elide.annotation.Include;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.job.Job;

import jakarta.persistence.*;

        import java.sql.Types;
import java.util.UUID;

@Include
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

    @ManyToOne
    private Job job;
}
