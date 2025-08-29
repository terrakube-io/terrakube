package io.terrakube.api.rs.hooks.module;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.plugin.scheduler.module.DeleteStorageCacheJob;
import io.terrakube.api.plugin.scheduler.module.ModuleRefreshService;
import io.terrakube.api.plugin.vcs.provider.github.GitHubTokenService;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.vcs.VcsConnectionType;
import io.terrakube.api.rs.vcs.VcsType;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AllArgsConstructor
@Slf4j
public class ModuleManageHook implements LifeCycleHook<Module> {

    Scheduler scheduler;
    GitHubTokenService gitHubTokenService;
    ModuleRefreshService moduleRefreshService;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase transactionPhase, Module module, RequestScope requestScope,
            Optional<ChangeSpec> optional) {
        log.info("ModuleManageHook {}/{}/{} phase {}", module.getOrganization().getName(), module.getName(), module.getProvider(), transactionPhase);
        switch (operation) {
            case CREATE:
                log.info("ModuleManageHook creation hook for {}/{}/{}", module.getOrganization().getName(),
                        module.getName(), module.getProvider());
                switch (transactionPhase) {
                    case PRECOMMIT:
                        checkAndCreateGitHubAppToken(module);
                        break;
                    case POSTCOMMIT:
                        try {
                            log.info("Create module refresh task for {}/{}/{}", module.getOrganization().getName(), module.getName(), module.getProvider());
                            moduleRefreshService.createTask(300, module.getId().toString(), true);
                        } catch (SchedulerException e) {
                            log.error("Failed to create module refresh task for {}/{}/{}, error {}",
                                    module.getOrganization().getName(), module.getName(), module.getProvider(), e.getMessage());
                        }
                    default:
                        break;
                }
                break;
            case UPDATE:
                checkNextModuleRefresh(module);
                break;
            case DELETE:
                try {
                    log.warn("ModuleManageHook Delete Storage for {}/{}/{}", module.getOrganization().getName(),
                            module.getName(), module.getProvider());
                    JobDataMap jobDataMap = new JobDataMap();
                    jobDataMap.put("moduleOrganization", module.getOrganization().getName());
                    jobDataMap.put("moduleName", module.getName());
                    jobDataMap.put("moduleProvider", module.getProvider());

                    JobDetail jobDetail = JobBuilder.newJob().ofType(DeleteStorageCacheJob.class)
                            .storeDurably()
                            .setJobData(jobDataMap)
                            .withIdentity(moduleRefreshService.getJobPrefix() + "_" + UUID.randomUUID())
                            .withDescription("ModuleDeleteStorage")
                            .build();

                    Trigger trigger = TriggerBuilder.newTrigger()
                            .startNow()
                            .forJob(jobDetail)
                            .withIdentity(moduleRefreshService.getJobPrefix() + "_" + UUID.randomUUID())
                            .withDescription("ModuleDeleteStorageV1")
                            .startNow()
                            .build();

                    log.info("Create Schedule to delete module cache form storage: {}", jobDetail.getKey());
                    scheduler.scheduleJob(jobDetail, trigger);
                } catch (SchedulerException e) {
                    log.error(e.getMessage());
                }
                break;
            default:
                log.warn("Hook not supported in module");
                break;
        }
    }

    private void checkNextModuleRefresh(Module module) {
        log.info("ModuleManageHook update hook for {}/{}/{}", module.getOrganization().getName(),
                module.getName(),
                module.getProvider());
        try {
            String triggerKey = moduleRefreshService.getJobPrefix() + module.getId();
            Trigger trigger = scheduler.getTrigger(TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey)
                    .build().getKey());

            if (trigger != null) {
                Date nextFireTime = trigger.getNextFireTime();
                log.info("Next trigger fire time for module {}/{}/{}: {}",
                        module.getOrganization().getName(),
                        module.getName(),
                        module.getProvider(),
                        nextFireTime);
            }
        } catch (SchedulerException e) {
            log.error("Failed to get next trigger time: {}", e.getMessage());
        }
    }

    private void checkAndCreateGitHubAppToken(Module module) {
        if (module.getVcs() == null || module.getVcs().getConnectionType() == VcsConnectionType.OAUTH
                || module.getVcs().getVcsType() != VcsType.GITHUB)
            return;
        String[] ownerAndRepo;
        try {
            ownerAndRepo = Arrays
                    .copyOfRange(new URI(module.getSource()).getPath().replaceAll("\\.git$", "").split("/"), 1, 3);
            gitHubTokenService.getGitHubAppToken(module.getVcs(), ownerAndRepo);
            log.debug("Successfully fetched GitHub App Token for module {}/{}/{}", module.getOrganization().getName(),
                    module.getName(), module.getProvider());
        } catch (URISyntaxException | JsonProcessingException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            log.error("Failed to fetch GitHub App Token for module {}/{}/{}, error {}",
                    module.getOrganization().getName(), module.getName(), module.getProvider(), e.getMessage());
        }
    }
}
