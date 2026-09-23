package io.terrakube.api;

import io.terrakube.api.plugin.token.pat.PatService;
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.rs.token.pat.Pat;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PatServiceSourceTests extends ServerApplicationTests {

    @Autowired
    PatService patService;
    @Autowired
    PatRepository patRepository;

    private static UUID jti(String jws) {
        return UUID.fromString(new String(Base64.getUrlDecoder().decode(jws.split("\\.")[1]))
            .replaceAll(".*\"jti\":\"([^\"]+)\".*", "$1"));
    }

    @Test
    void sixArgOverloadStoresSource() {
        JSONArray groups = new JSONArray();
        groups.appendElement("TERRAKUBE_DEVELOPERS");
        String jws = patService.createToken(7, "cli", "N", "e@e.io", groups, "CLI_LOGIN");
        Pat pat = patRepository.findById(jti(jws)).orElseThrow();
        assertEquals("CLI_LOGIN", pat.getSource());
    }

    @Test
    void legacyOverloadDefaultsToApiSource() {
        JSONArray groups = new JSONArray();
        String jws = patService.createToken(7, "legacy", "N", "e@e.io", groups);
        assertEquals("API", patRepository.findById(jti(jws)).orElseThrow().getSource());
    }

    @Test
    void touchLastUsedSetsTimestamp() {
        JSONArray groups = new JSONArray();
        String jws = patService.createToken(7, "t", "N", "e@e.io", groups, "CLI_LOGIN");
        UUID id = jti(jws);
        assertNull(patRepository.findById(id).orElseThrow().getLastUsedAt());
        patService.touchLastUsed(id);
        assertNotNull(patRepository.findById(id).orElseThrow().getLastUsedAt());
    }
}
