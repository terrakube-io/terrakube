package io.terrakube.api.rs.federated;

import com.yahoo.elide.annotation.*;
import io.terrakube.api.rs.federated.claim.FederatedClaim;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

@ReadPermission(expression = "user is a superuser")
@CreatePermission(expression = "user is a superuser")
@UpdatePermission(expression = "user is a superuser")
@DeletePermission(expression = "user is a superuser")
@Include(rootLevel = true)
@Getter
@Setter
@Entity(name = "federated")
public class Federated {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    @NotBlank(message = "name is required")
    String name;

    @Column(name = "issuer_url")
    @NotBlank(message = "issuerUrl is required")
    String issuerUrl;

    @Column(name = "audience")
    @NotBlank(message = "audience is required")
    String audience;

    @OneToMany(mappedBy = "federated", fetch = FetchType.EAGER)
    private List<FederatedClaim> claims;

}
