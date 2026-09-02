package io.terrakube.api.rs.provider.implementation;

import com.yahoo.elide.annotation.Include;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

import jakarta.persistence.*;

import java.sql.Types;
import java.util.UUID;

@Include
@Getter
@Setter
@Entity(name = "implementation")
public class Implementation {

    @Id
    @JdbcTypeCode(Types.VARCHAR)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name="os")
    private String os;

    @Column(name="arch")
    private String arch;

    @Column(name="filename")
    private String filename;

    @Column(name="download_url")
    private String downloadUrl;

    @Column(name="shasums_url")
    private String shasumsUrl;

    @Column(name="shasums_signature_url")
    private String shasumsSignatureUrl;

    @Column(name="shasum")
    private String shasum;

    @Column(name="key_id")
    private String keyId;

    @Column(name="ascii_armor")
    private String asciiArmor;

    @Column(name="trust_signature")
    private String trustSignature;

    @Column(name="source")
    private String source;

    @Column(name="source_url")
    private String sourceUrl;

    @ManyToOne
    private Version version;
}
