package io.terrakube.registry.service.mirror;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorDownloadGuardTest {

    private static final byte[] PUBLIC_IP = {93, (byte) 184, (byte) 216, 34};

    @TempDir
    Path tempDir;

    private WireMockServer upstream;
    private MirrorDownloadGuard guard;

    /** The stub runs on localhost; pretend it resolves to a public address so only the hops under test are refused. */
    static MirrorDownloadGuard.Resolver localhostIsPublic() {
        return host -> "localhost".equals(host)
                ? new InetAddress[]{InetAddress.getByAddress(host, PUBLIC_IP)}
                : InetAddress.getAllByName(host);
    }

    @BeforeEach
    void setUp() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("localhost"));
        upstream.start();
        guard = new MirrorDownloadGuard(localhostIsPublic(), true, Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() {
        upstream.stop();
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + upstream.port() + path);
    }

    @Test
    void rejectsPrivateLoopbackLinkLocalAndUla() {
        MirrorDownloadGuard strict = new MirrorDownloadGuard(InetAddress::getAllByName, false, Duration.ofSeconds(5));
        for (String host : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "0.0.0.0", "[::1]", "[fc00::1]", "[fd12:3456::1]", "[fe80::1]", "224.0.0.1"}) {
            assertThatThrownBy(() -> strict.check(URI.create("https://" + host + "/x")))
                    .as(host).isInstanceOf(MirrorUpstreamException.class);
        }
    }

    @Test
    void rejectsPlainHttp() {
        MirrorDownloadGuard strict = new MirrorDownloadGuard(localhostIsPublic(), false, Duration.ofSeconds(5));
        assertThatThrownBy(() -> strict.check(URI.create("http://localhost/x")))
                .isInstanceOf(MirrorUpstreamException.class).hasMessageContaining("non-https");
        assertThatThrownBy(() -> strict.check(URI.create("file:///etc/passwd")))
                .isInstanceOf(MirrorUpstreamException.class);
    }

    @Test
    void acceptsPublicAddress() {
        MirrorDownloadGuard strict = new MirrorDownloadGuard(host -> new InetAddress[]{InetAddress.getByAddress(host, PUBLIC_IP)},
                false, Duration.ofSeconds(5));
        assertThatCode(() -> strict.check(URI.create("https://registry.example.com/v1/providers/")))
                .doesNotThrowAnyException();
    }

    @Test
    void followsRedirectsAndReturnsTheBody() {
        upstream.stubFor(get("/a").willReturn(aResponse().withStatus(302).withHeader("Location", "/b")));
        upstream.stubFor(get("/b").willReturn(aResponse().withBody("hello")));

        assertThat(guard.getString(url("/a"))).contains("hello");
        assertThat(guard.getString(url("/missing"))).isEmpty();
    }

    @Test
    void redirectToPrivateAddressIsRejected() {
        upstream.stubFor(get("/dl").willReturn(aResponse().withStatus(302)
                .withHeader("Location", "http://127.0.0.1:" + upstream.port() + "/secret")));
        upstream.stubFor(get("/secret").willReturn(aResponse().withBody("internal")));

        assertThatThrownBy(() -> guard.download(url("/dl"), tempDir.resolve("a.zip"), 1024))
                .isInstanceOf(MirrorUpstreamException.class).hasMessageContaining("127.0.0.1");
        upstream.verify(0, getRequestedFor(urlEqualTo("/secret")));
    }

    @Test
    void reservedIpv4RangesAndIpv6FormsCarryingThemAreInternal() throws Exception {
        for (String internal : new String[]{
                "100.64.0.1", "100.127.255.254",            // carrier-grade NAT
                "0.0.0.1", "0.255.255.255",                 // this network
                "198.18.0.1", "198.19.255.254",             // benchmarking
                "240.0.0.1", "255.255.255.255",             // reserved, broadcast
                "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe",    // NAT64 of 127.0.0.1, 169.254.169.254
                "2002:a00:1::", "2002:c0a8:101::1",         // 6to4 of 10.0.0.1, 192.168.1.1
                "::a00:1", "::6440:1"}) {                   // IPv4-compatible 10.0.0.1, 100.64.0.1
            assertThat(MirrorDownloadGuard.isInternal(InetAddress.getByName(internal))).as(internal).isTrue();
        }
        for (String external : new String[]{"100.63.255.255", "100.128.0.1", "198.17.0.1", "198.20.0.1",
                "93.184.216.34", "64:ff9b::5db8:d822", "2002:5db8:d822::",
                "::5db8:d822", "2606:4700::1111"}) {
            assertThat(MirrorDownloadGuard.isInternal(InetAddress.getByName(external))).as(external).isFalse();
        }
    }

    @Test
    void stopsAfterFiveRedirects() {
        upstream.stubFor(get("/loop").willReturn(aResponse().withStatus(302).withHeader("Location", "/loop")));

        assertThatThrownBy(() -> guard.getString(url("/loop")))
                .isInstanceOf(MirrorUpstreamException.class).hasMessageContaining("redirects");
        upstream.verify(MirrorDownloadGuard.MAX_REDIRECTS + 1, getRequestedFor(urlEqualTo("/loop")));
    }

    @Test
    void abortsAboveSizeCap() throws Exception {
        upstream.stubFor(get("/big").willReturn(aResponse().withBody(new byte[4096])));
        upstream.stubFor(get("/small").willReturn(aResponse().withBody(new byte[1024])));

        assertThatThrownBy(() -> guard.download(url("/big"), tempDir.resolve("big.zip"), 1024))
                .isInstanceOf(MirrorUpstreamException.class).hasMessageContaining("larger than");

        Path small = tempDir.resolve("small.zip");
        guard.download(url("/small"), small, 1024);
        assertThat(Files.size(small)).isEqualTo(1024);
    }

    @Test
    void stopsAtTheDeadline() {
        upstream.stubFor(get("/slow").willReturn(aResponse().withBody("late").withFixedDelay(3000)));
        MirrorDownloadGuard impatient = new MirrorDownloadGuard(localhostIsPublic(), true, Duration.ofMillis(300));

        assertThatThrownBy(() -> impatient.getString(url("/slow"))).isInstanceOf(MirrorUpstreamException.class);
    }

    @Test
    void anErrorStatusFails() {
        upstream.stubFor(get("/boom").willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> guard.getString(url("/boom")))
                .isInstanceOf(MirrorUpstreamException.class).hasMessageContaining("500");
    }
}
