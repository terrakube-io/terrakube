package io.terrakube.api.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.terrakube.api.plugin.routing.PublicUrls;

class PublicUrlsTest {

    @Test
    void splitDomainKeepsTodaysApiBase() {
        PublicUrls urls = new PublicUrls("", "terrakube-api.example.com");
        assertFalse(urls.isSingleDomain());
        assertEquals("https://terrakube-api.example.com", urls.apiBaseUrl());
        assertEquals("", urls.apiPathPrefix());
        assertEquals("", urls.publicHost());
    }

    @Test
    void singleDomainUsesCanonicalApiPrefix() {
        PublicUrls urls = new PublicUrls("https://app.example.com", "");
        assertTrue(urls.isSingleDomain());
        assertEquals("https://app.example.com/api", urls.apiBaseUrl());
        assertEquals("/api", urls.apiPathPrefix());
        assertEquals("app.example.com", urls.publicHost());
    }

    @Test
    void explicitHostnameWinsOverDerivedApiBase() {
        PublicUrls urls = new PublicUrls("https://app.example.com", "api.example.com");
        assertTrue(urls.isSingleDomain());
        assertEquals("https://api.example.com", urls.apiBaseUrl());
    }

    @Test
    void issuerHostPrefersExplicitHostnameThenPublicHost() {
        assertEquals("api.example.com", new PublicUrls("https://app.example.com", "api.example.com").issuerHost());
        assertEquals("app.example.com", new PublicUrls("https://app.example.com", "").issuerHost());
        assertEquals("api.example.com", new PublicUrls("", "api.example.com").issuerHost());
    }

    @Test
    void publicHostKeepsPort() {
        assertEquals("localhost:8443", new PublicUrls("https://localhost:8443", "").publicHost());
    }

    @Test
    void neitherSettingFailsStartup() {
        assertThrows(IllegalStateException.class, () -> new PublicUrls("", ""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://app.example.com/", "https://app.example.com/terrakube", "app.example.com",
        "ftp://app.example.com", "https://app.example.com?x=1", "https://user@app.example.com"
    })
    void malformedPublicUrlFailsStartup(String value) {
        assertThrows(IllegalStateException.class, () -> new PublicUrls(value, ""));
    }
}
