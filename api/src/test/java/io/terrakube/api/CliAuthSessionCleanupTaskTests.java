package io.terrakube.api;

import io.terrakube.api.plugin.token.login.CliAuthSessionCleanupTask;
import io.terrakube.api.repository.CliAuthSessionRepository;
import io.terrakube.api.rs.token.login.CliAuthSession;
import io.terrakube.api.rs.token.login.CliAuthSessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class CliAuthSessionCleanupTaskTests extends ServerApplicationTests {

    @Autowired
    CliAuthSessionCleanupTask task;
    @Autowired
    CliAuthSessionRepository repository;

    @Test
    @Transactional
    void purgesOnlyExpiredRows() {
        repository.save(newSession("live", new Date(System.currentTimeMillis() + 60000)));
        repository.save(newSession("dead", new Date(System.currentTimeMillis() - 60000)));

        int deleted = task.purgeExpired();

        assertTrue(deleted >= 1);
        assertTrue(repository.findAll().stream().anyMatch(s -> "live".equals(s.getCliState())));
        assertFalse(repository.findAll().stream().anyMatch(s -> "dead".equals(s.getCliState())));
    }

    private CliAuthSession newSession(String state, Date expiresAt) {
        CliAuthSession s = new CliAuthSession();
        s.setStatus(CliAuthSessionStatus.PENDING_IDP);
        s.setCliRedirectUri("http://localhost:10000/login");
        s.setCliCodeChallenge("c");
        s.setCliState(state);
        s.setExpiresAt(expiresAt);
        return s;
    }
}
