package io.terrakube.api.rs.hooks.provider;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.scheduler.provider.ProviderRefreshService;
import io.terrakube.api.rs.provider.Provider;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

import java.util.Date;
import java.util.Optional;

/**
 * Elide lifecycle hook for the Provider entity.
 * On CREATE: schedules a ProviderRefreshJob to immediately fetch versions from the public registry.
 * On UPDATE: ensures a refresh job exists, creates one if missing.
 */
@AllArgsConstructor
@Slf4j
public class ProviderManageHook implements LifeCycleHook<Provider> {

    Scheduler scheduler;
    ProviderRefreshService providerRefreshService;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
                        LifeCycleHookBinding.TransactionPhase transactionPhase,
                        Provider provider, RequestScope requestScope,
                        Optional<ChangeSpec> optional) {

        log.info("ProviderManageHook {} phase {} for provider {}",
                operation, transactionPhase, provider.getName());

        switch (operation) {
            case CREATE:
                if (transactionPhase == LifeCycleHookBinding.TransactionPhase.POSTCOMMIT) {
                    // Only schedule refresh for namespace/name format providers (from public registry)
                    if (provider.getName() != null && provider.getName().contains("/")) {
                        try {
                            log.info("Scheduling provider refresh for {}", provider.getName());
                            providerRefreshService.createTask(300, provider.getId().toString(), true);
                        } catch (SchedulerException e) {
                            log.error("Failed to create provider refresh task for {}: {}",
                                    provider.getName(), e.getMessage());
                        }
                    }
                }
                break;
            case UPDATE:
                checkNextProviderRefresh(provider);
                break;
            default:
                break;
        }
    }

    /**
     * On update, check if a refresh trigger exists. If not, create one.
     */
    private void checkNextProviderRefresh(Provider provider) {
        if (provider.getName() == null || !provider.getName().contains("/")) return;

        try {
            String triggerKey = providerRefreshService.getJobPrefix() + provider.getId();
            Trigger trigger = scheduler.getTrigger(TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .build().getKey());

            if (trigger != null) {
                Date nextFireTime = trigger.getNextFireTime();
                log.info("Next provider refresh for {}: {}", provider.getName(), nextFireTime);
            } else {
                log.info("No refresh trigger found for {}, creating one", provider.getName());
                providerRefreshService.createTask(300, provider.getId().toString(), true);
            }
        } catch (SchedulerException e) {
            log.error("Failed to check/create provider refresh trigger: {}", e.getMessage());
        }
    }
}
