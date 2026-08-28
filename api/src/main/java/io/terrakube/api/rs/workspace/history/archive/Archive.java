package io.terrakube.api.rs.workspace.history.archive;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import io.terrakube.api.rs.workspace.history.History;

import jakarta.persistence.*;

import java.sql.Types;
import java.util.UUID;

@Getter
@Setter
@Entity(name = "temp_archive")
public class Archive {
    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type")
    private ArchiveType type;

    @ManyToOne
    private History history;
}
