package io.terrakube.api.plugin.token.login;

import io.terrakube.api.repository.CliAuthSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

@Slf4j
@Component
@RequiredArgsConstructor
public class CliAuthSessionCleanupTask {

    private final CliAuthSessionRepository repository;

    @Scheduled(fixedDelayString = "${io.terrakube.token.login.cleanup-interval-ms:300000}")
    @Transactional
    public int purgeExpired() {
        long deleted = repository.deleteByExpiresAtBefore(new Date());
        if (deleted > 0) {
            log.info("Purged {} expired cli_auth_session rows", deleted);
        }
        return (int) deleted;
    }
}
