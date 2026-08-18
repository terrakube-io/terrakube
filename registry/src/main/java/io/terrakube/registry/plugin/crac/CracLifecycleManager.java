package io.terrakube.registry.plugin.crac;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;
import org.springframework.stereotype.Component;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;

@Slf4j
@Component
public class CracLifecycleManager implements Resource {

    @PostConstruct
    public void registerCracResource() {
        log.info("Registering CRaC resource hook for Terrakube Registry");
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
        log.info("CRaC beforeCheckpoint triggered: Preparing Terrakube Registry state for checkpointing...");
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception {
        long restoreStartTime = System.currentTimeMillis();
        log.info("CRaC afterRestore triggered: Re-initializing Terrakube Registry state at {}", Instant.now());

        // Re-seed SecureRandom to ensure cryptographic entropy uniqueness across restored pods
        try {
            SecureRandom secureRandom = SecureRandom.getInstanceStrong();
            byte[] seed = secureRandom.generateSeed(32);
            new SecureRandom().setSeed(seed);
            log.info("SecureRandom successfully re-seeded with fresh system entropy post-restore");
        } catch (NoSuchAlgorithmException e) {
            log.warn("Failed to get strong SecureRandom instance, falling back to default re-seed", e);
            new SecureRandom().setSeed(System.currentTimeMillis());
        }

        log.info("Terrakube Registry CRaC post-restore initialization completed in {} ms", (System.currentTimeMillis() - restoreStartTime));
    }
}
