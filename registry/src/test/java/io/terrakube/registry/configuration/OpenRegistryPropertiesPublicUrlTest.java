package io.terrakube.registry.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OpenRegistryPropertiesPublicUrlTest {

    @Test
    void splitDomainLeavesLoginApiUrlUnset() {
        OpenRegistryProperties props = new OpenRegistryProperties();
        props.setPublicUrl("");
        props.validate();
        assertNull(props.getLoginApiUrl());
    }

    @Test
    void singleDomainDerivesLoginApiUrl() {
        OpenRegistryProperties props = new OpenRegistryProperties();
        props.setPublicUrl("https://app.example.com");
        props.validate();
        assertEquals("https://app.example.com/api", props.getLoginApiUrl());
    }

    @Test
    void explicitLoginApiUrlWins() {
        OpenRegistryProperties props = new OpenRegistryProperties();
        props.setPublicUrl("https://app.example.com");
        props.setLoginApiUrl("https://api.example.com");
        props.validate();
        assertEquals("https://api.example.com", props.getLoginApiUrl());
    }

    @Test
    void malformedPublicUrlFailsStartup() {
        OpenRegistryProperties props = new OpenRegistryProperties();
        props.setPublicUrl("app.example.com/terrakube");
        assertThrows(IllegalStateException.class, props::validate);
    }
}
