package io.terrakube.api;

import io.terrakube.api.repository.CliAuthSessionRepository;
import io.terrakube.api.rs.token.login.CliAuthSession;
import io.terrakube.api.rs.token.login.CliAuthSessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CliAuthSessionRepositoryTests extends ServerApplicationTests {

    @Autowired
    CliAuthSessionRepository repository;

    @Test
    void persistsAndFindsByAuthCodeHash() {
        CliAuthSession s = new CliAuthSession();
        s.setStatus(CliAuthSessionStatus.CODE_ISSUED);
        s.setCliRedirectUri("http://localhost:10000/login");
        s.setCliCodeChallenge("abc");
        s.setCliState("cli-state");
        s.setAuthCodeHash("hash-1");
        s.setChosenDays(30);
        s.setCodeExpiresAt(new Date(System.currentTimeMillis() + 60000));
        s.setExpiresAt(new Date(System.currentTimeMillis() + 600000));
        repository.save(s);

        assertTrue(repository.findByAuthCodeHash("hash-1").isPresent());
        assertEquals(CliAuthSessionStatus.CODE_ISSUED,
            repository.findByAuthCodeHash("hash-1").get().getStatus());
    }

    @Test
    @Transactional
    void deletesExpiredRows() {
        CliAuthSession s = new CliAuthSession();
        s.setStatus(CliAuthSessionStatus.PENDING_IDP);
        s.setCliRedirectUri("http://localhost:10000/login");
        s.setCliCodeChallenge("abc");
        s.setCliState(UUID.randomUUID().toString());
        s.setExpiresAt(new Date(System.currentTimeMillis() - 1000));
        repository.save(s);

        long deleted = repository.deleteByExpiresAtBefore(new Date());
        assertTrue(deleted >= 1);
    }
}
