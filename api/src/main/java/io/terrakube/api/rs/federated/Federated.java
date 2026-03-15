package io.terrakube.api.rs.federated;

import com.yahoo.elide.annotation.*;
import io.terrakube.api.rs.IdConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import java.sql.Types;
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
    @Convert(converter = IdConverter.class)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name")
    String name;

    @Column(name = "issuer_url")
    String issuerUrl;

    @Column(name = "audience")
    String audience;

}
