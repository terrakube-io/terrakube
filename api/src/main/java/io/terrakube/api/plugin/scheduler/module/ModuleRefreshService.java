package io.terrakube.api.plugin.scheduler.module;

import java.util.List;

import io.terrakube.api.repository.OrganizationRepository;
import org.quartz.Job;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.terrakube.api.plugin.scheduler.ScheduleServiceBase;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ModuleVersionRepository;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.module.ModuleVersion;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Service
@Getter
@Slf4j
public class ModuleRefreshService extends ScheduleServiceBase {
    private final String jobPrefix = "TerrakubeV2_ModuleRefresh_";
    private final Class<? extends Job> jobClass = ModuleRefreshJob.class;
    private final String jobType = "ModuleRefresh";
    private final String jobDataKey = "moduleId";

    @Autowired
    private ModuleRepository moduleRepository;
    @Autowired
    private ModuleVersionRepository moduleVersionRepository;
    @Autowired
    private OrganizationRepository organizationRepository;

    @PostConstruct
    @Transactional
    public void initModuleRefreshJob() {
        List<Module> modules = moduleRepository.findByOrganizationIn(organizationRepository.findAll()); // this will return only enabled organization modules

        for (Module module : modules) {
            List<ModuleVersion> versions = moduleVersionRepository.findAllByModuleId(module.getId());
            if (versions != null && !versions.isEmpty()) {
                log.debug("Module {}/{}/{} has prefetched versions", module.getOrganization().getName(),
                        module.getName(), module.getProvider());
                continue;
            }
            log.info("Module {}/{}/{} has no versions in the database, creating a scheduler job to fetch the versions",
                    module.getOrganization().getName(), module.getName(), module.getProvider());
            try {
                createTask(300, module.getId().toString(), true);
            } catch (SchedulerException e) {
                log.error("Failed to create module refresh task for {}/{}/{}, error {}",
                        module.getOrganization().getName(), module.getName(), module.getProvider(), e);
            }
        }
    }

}
