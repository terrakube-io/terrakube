package io.terrakube.api.plugin.token.login;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CliLoginCookieTest {

    // any base64url string works as the HMAC seed
    private final CliLoginCookie cookie =
        new CliLoginCookie("ejZRSFgheUBOZXAyUURUITUzdmdINDNeUGpSWHlDM1g=");

    @Test
    void signAndVerifyRoundTrip() {
        String sid = "11111111-1111-1111-1111-111111111111";
        String signed = cookie.sign(sid);
        assertTrue(signed.startsWith(sid + "."));
        assertEquals(Optional.of(sid), cookie.verify(signed));
    }

    @Test
    void tamperedValueIsRejected() {
        String sid = "11111111-1111-1111-1111-111111111111";
        String signed = cookie.sign(sid);
        assertEquals(Optional.empty(), cookie.verify(signed + "x"));
        assertEquals(Optional.empty(),
            cookie.verify("22222222-2222-2222-2222-222222222222." + signed.split("\\.")[1]));
        assertEquals(Optional.empty(), cookie.verify("garbage"));
        assertEquals(Optional.empty(), cookie.verify(null));
    }

    @Test
    void builtCookieHasHardenedAttributes() {
        ResponseCookie rc = cookie.build("11111111-1111-1111-1111-111111111111");
        assertEquals("tk_cli_login", rc.getName());
        assertTrue(rc.isHttpOnly());
        assertTrue(rc.isSecure());
        assertEquals("Lax", rc.getSameSite());
        assertEquals("/oauth", rc.getPath());
        assertEquals(600, rc.getMaxAge().getSeconds());
    }
}
