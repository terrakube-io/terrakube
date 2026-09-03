package io.terrakube.api.rs.federated.claim;

import com.yahoo.elide.annotation.*;
import io.terrakube.api.rs.federated.Federated;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.UUID;

@ReadPermission(expression = "user is a superuser")
@CreatePermission(expression = "user is a superuser")
@UpdatePermission(expression = "user is a superuser")
@DeletePermission(expression = "user is a superuser")
@Include(rootLevel = false)
@Getter
@Setter
@Entity(name = "federated_claim")
public class FederatedClaim {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "claim_key")
    @NotBlank(message = "claimKey is required")
    private String claimKey;

    @Column(name = "claim_value")
    @NotBlank(message = "claimValue is required")
    private String claimValue;

    @ManyToOne
    @JoinColumn(name = "federated_id")
    private Federated federated;
}
