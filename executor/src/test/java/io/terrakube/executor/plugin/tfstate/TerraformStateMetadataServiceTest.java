package io.terrakube.executor.plugin.tfstate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerraformStateMetadataServiceTest {

    private TerraformStateMetadataService service;

    @BeforeEach
    void setUp() {
        service = new TerraformStateMetadataService(new ObjectMapper());
    }

    @Test
    void extractsSerialLineageAndMd5FromValidState() {
        String rawState = """
                {
                  "version": 4,
                  "terraform_version": "1.5.7",
                  "serial": 42,
                  "lineage": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
                  "outputs": {},
                  "resources": []
                }
                """;

        TerraformStateMetadata metadata = service.extractMetadata(rawState);

        assertThat(metadata.getSerial()).isEqualTo(42);
        assertThat(metadata.getLineage()).isEqualTo("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d");
        assertThat(metadata.getMd5()).isEqualTo(DigestUtils.md5Hex(rawState));
    }

    @Test
    void handlesNullOrEmptyRawStateGracefully() {
        TerraformStateMetadata nullMetadata = service.extractMetadata(null);
        assertThat(nullMetadata.getSerial()).isEqualTo(1);
        assertThat(nullMetadata.getLineage()).isEqualTo("0");
        assertThat(nullMetadata.getMd5()).isEqualTo("0");

        TerraformStateMetadata blankMetadata = service.extractMetadata("   ");
        assertThat(blankMetadata.getSerial()).isEqualTo(1);
        assertThat(blankMetadata.getLineage()).isEqualTo("0");
        assertThat(blankMetadata.getMd5()).isEqualTo("0");
    }

    @Test
    void handlesMalformedJsonGracefullyWithFallbackValues() {
        String malformed = "{ this is not valid json }";

        TerraformStateMetadata metadata = service.extractMetadata(malformed);

        assertThat(metadata.getSerial()).isEqualTo(1);
        assertThat(metadata.getLineage()).isEqualTo("0");
        assertThat(metadata.getMd5()).isEqualTo(DigestUtils.md5Hex(malformed));
    }

    @Test
    void noChangeStateYieldsIdenticalSerialAndMd5() {
        String state = "{\"serial\": 10, \"lineage\": \"abc\", \"resources\": []}";

        TerraformStateMetadata meta1 = service.extractMetadata(state);
        TerraformStateMetadata meta2 = service.extractMetadata(state);

        assertThat(meta1.getSerial()).isEqualTo(meta2.getSerial());
        assertThat(meta1.getMd5()).isEqualTo(meta2.getMd5());
    }
}
