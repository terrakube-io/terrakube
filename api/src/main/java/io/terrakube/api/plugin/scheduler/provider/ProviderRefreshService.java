package io.terrakube.api.plugin.scheduler.provider;

import io.terrakube.api.plugin.scheduler.ScheduleServiceBase;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.ProviderRepository;
import io.terrakube.api.repository.ProviderVersionRepository;
import io.terrakube.api.rs.provider.Provider;
import io.terrakube.api.rs.provider.implementation.Version;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Schedules Quartz jobs to periodically refresh provider versions from the public
 * Terraform Registry. Mirrors the ModuleRefreshService pattern.
 *
 * On startup, creates a refresh job for every provider that has no versions yet.
 * The ProviderManageHook also calls createTask() when a new provider is created.
 *
 * Each job runs every 300 seconds (5 minutes) and checks registry.terraform.io
 * for new versions, importing any that are missing from the local database.
 */
@Service
@Getter
@Slf4j
public class ProviderRefreshService extends ScheduleServiceBase {

    private final String jobPrefix = "TerrakubeV2_ProviderRefresh_";
    private final Class<? extends Job> jobClass = ProviderRefreshJob.class;
    private final String jobType = "ProviderRefresh";
    private final String jobDataKey = "providerId";

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private ProviderVersionRepository providerVersionRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    /**
     * On application startup, schedule refresh jobs for providers that have no versions.
     * Providers that already have versions are considered up-to-date (the periodic job
     * will pick up new versions on its next run).
     */
    @PostConstruct
    @Transactional
    public void initProviderRefreshJobs() {
        List<Provider> providers = providerRepository.findByOrganizationIn(
                organizationRepository.findAll()
        );

        for (Provider provider : providers) {
            List<Version> versions = providerVersionRepository.findAllByProviderId(provider.getId());
            if (versions != null && !versions.isEmpty()) {
                log.debug("Provider {} already has {} versions, skipping initial refresh",
                        provider.getName(), versions.size());
                continue;
            }

            // Provider has no versions — only valid for namespace/name format providers
            if (!provider.getName().contains("/")) {
                log.debug("Provider '{}' is not in namespace/name format, skipping refresh",
                        provider.getName());
                continue;
            }

            log.info("Provider {} has no versions, scheduling refresh job", provider.getName());
            try {
                createTask(300, provider.getId().toString(), true);
            } catch (SchedulerException e) {
                log.error("Failed to create provider refresh task for {}: {}",
                        provider.getName(), e.getMessage());
            }
        }
    }
}
