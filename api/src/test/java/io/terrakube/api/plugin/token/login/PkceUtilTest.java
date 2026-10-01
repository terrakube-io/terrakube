package io.terrakube.api.plugin.token.login;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PkceUtilTest {

    @Test
    void knownVectorFromRfc7636() {
        // RFC 7636 Appendix B
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            PkceUtil.codeChallengeS256(verifier));
    }

    @Test
    void verifyRoundTrip() {
        String v = PkceUtil.generateCodeVerifier();
        assertTrue(v.length() >= 43 && v.length() <= 128);
        assertTrue(PkceUtil.verifyS256(v, PkceUtil.codeChallengeS256(v)));
        assertFalse(PkceUtil.verifyS256(v, PkceUtil.codeChallengeS256(v + "x")));
    }
}
