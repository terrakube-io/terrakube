package io.terrakube.api.plugin.scheduler.module;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.zafarkhaja.semver.ParseException;
import com.github.zafarkhaja.semver.Version;
import io.terrakube.api.plugin.ssh.TerrakubeSshdSessionFactory;
import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsTokenService;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ModuleVersionRepository;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.module.ModuleVersion;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsConnectionType;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.*;

@Slf4j
@Component
public class ModuleRefreshJob implements Job {

    private static final int GIT_TIMEOUT_SECONDS = 30;
    @Autowired
    private ModuleRefreshService moduleRefreshService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private ModuleRepository moduleRepository;
    @Autowired
    private ModuleVersionRepository moduleVersionRepository;
    @Autowired
    private AzDevOpsTokenService azDevOpsTokenService;

    @Override
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String moduleId = context.getJobDetail().getJobDataMap().getString(moduleRefreshService.getJobDataKey());
        Optional<Module> search = moduleRepository.findById(UUID.fromString(moduleId));
        if (search.isEmpty()) {
            deleteModuleTask(moduleId, "the module no longer exists");
            return;
        }

        Module module = search.get();
        if (module.getOrganization() == null) {
            deleteModuleTask(moduleId, "its organization is disabled or no longer available");
            return;
        }

        String organizationName = module.getOrganization().getName();
        log.info("Refreshing module {} on {}", module.getName(), organizationName);
        Map<String, Ref> rawRepoTags = null;

        try {
            rawRepoTags = getVersionFromRepository(module.getSource(), module.getVcs(), module.getSsh());
        } catch (Exception e) {
            // Broad catch is intentional: this is a scheduled Quartz job refreshing one module among many.
            // Any VCS/network failure here must not propagate and abort the whole job execution.
            log.error("Failed to refresh module {} on organization/user {}, error {}", module.getName(),
                    organizationName, e.getMessage());
        }

        if (rawRepoTags == null) {
            log.error("There are no tags available for module {} on organization/user {}, error {}", module.getName(),
                    organizationName, "No versions found");
            return;
        }

        Map<String, ModuleVersionNormalizer.NormalizedVersion> canonicalVersions =
                resolveCanonicalVersions(rawRepoTags, module.getTagPrefix(), module.getName());

        List<ModuleVersion> currentModuleVersion = Optional
                .ofNullable(moduleVersionRepository.findAllByModuleId(module.getId())).orElse(Collections.emptyList());
        List<String> currentDatabaseVersions = currentModuleVersion.stream().map(ModuleVersion::getVersion).toList();

        Map<String, Ref> resolvedRepoTags = rawRepoTags;
        List<ModuleVersion> newModuleVersions = new ArrayList<>();
        canonicalVersions.forEach((canonicalVersion, normalized) -> {
            if (currentDatabaseVersions.contains(canonicalVersion)) {
                return;
            }
            Ref ref = resolvedRepoTags.get(normalized.originalTag());
            if (ref == null || ref.getObjectId() == null) {
                log.warn("Skipping version {} (tag {}) for module {}: tag has no resolvable commit", canonicalVersion,
                        normalized.originalTag(), module.getName());
                return;
            }
            ModuleVersion moduleVersion = new ModuleVersion();
            moduleVersion.setVersion(canonicalVersion);
            moduleVersion.setGitTag(normalized.originalTag());
            moduleVersion.setCommit(ref.getObjectId().getName());
            moduleVersion.setModule(module);
            newModuleVersions.add(moduleVersion);
            log.info("Adding new version {} (tag {}) to module {}", canonicalVersion, normalized.originalTag(),
                    module.getName());
        });

        if (newModuleVersions.isEmpty()) {
            log.info("No new versions found for module {}", module.getName());
        } else {
            moduleVersionRepository.saveAll(newModuleVersions);
        }

        calculateLatestModuleVersion(module, organizationName);
    }

    Map<String, ModuleVersionNormalizer.NormalizedVersion> resolveCanonicalVersions(Map<String, Ref> rawRepoTags,
            String tagPrefix, String moduleName) {
        Map<String, List<String>> rawTagsByCanonicalVersion = new HashMap<>();
        rawRepoTags.keySet().forEach(rawTag -> ModuleVersionNormalizer.normalize(rawTag, tagPrefix).ifPresentOrElse(
                normalized -> rawTagsByCanonicalVersion
                        .computeIfAbsent(normalized.canonicalVersion(), key -> new ArrayList<>())
                        .add(rawTag),
                () -> log.info("Skipping non-SemVer or non-matching tag {} for module {}", rawTag, moduleName)));

        Map<String, ModuleVersionNormalizer.NormalizedVersion> canonicalVersions = new HashMap<>();
        rawTagsByCanonicalVersion.forEach((canonicalVersion, rawTags) -> {
            if (rawTags.size() > 1) {
                log.error("Module {} has a version collision for canonical version {}: tags {}", moduleName,
                        canonicalVersion, rawTags);
            } else {
                canonicalVersions.put(canonicalVersion,
                        new ModuleVersionNormalizer.NormalizedVersion(canonicalVersion, rawTags.get(0)));
            }
        });
        return canonicalVersions;
    }

    private void calculateLatestModuleVersion(Module module, String organizationName) {
        try {
            module.setLatestVersion(moduleVersionRepository.findAllByModuleId(module.getId()).stream()
                    .map(ModuleVersion::getVersion)
                    .filter(v -> {
                        try {
                            Version.parse(v);
                            return true;
                        } catch (ParseException e) {
                            return false;
                        }
                    })
                    .max(Comparator.comparing(Version::parse))
                    .orElse("Version pending"));
            log.info("Latest module {}/{} version {}", organizationName, module.getName(), module.getLatestVersion());
            moduleRepository.save(module);
        } catch (Exception e) {
            // Broad catch is intentional: a bad version string must not stop the whole refresh job.
            log.error("Failed to calculate latest module version {}/{}", organizationName, module.getName());
        }
    }

    private void deleteModuleTask(String moduleId, String reason) {
        try {
            log.warn("Removing stale module refresh task for module {} because {}", moduleId, reason);
            moduleRefreshService.deleteTask(moduleId);
        } catch (SchedulerException e) {
            log.error("Failed to delete stale module refresh task for module {}, error {}", moduleId, e.getMessage());
        }
    }

    private Map<String, Ref> getVersionFromRepository(String source, Vcs vcs, Ssh ssh)
            throws JsonProcessingException, NoSuchAlgorithmException, InvalidKeySpecException,
            URISyntaxException, GitAPIException {
        CredentialsProvider credentialsProvider = null;
        TransportConfigCallback transportConfigCallback = null;
        Map<String, Ref> tags = new HashMap<>(), originalTags = new HashMap<>();
        if (vcs != null) {
            log.info("vcs using {}", vcs.getVcsType().toString());
            switch (vcs.getVcsType()) {
                case GITHUB:
                    if (vcs.getConnectionType() == VcsConnectionType.OAUTH) {
                        credentialsProvider = new UsernamePasswordCredentialsProvider(vcs.getAccessToken(), "");
                    } else {
                        credentialsProvider = new UsernamePasswordCredentialsProvider("x-access-token",
                                tokenService.getAccessToken(source, vcs));
                    }
                    break;
                case BITBUCKET:
                    credentialsProvider = new UsernamePasswordCredentialsProvider("x-token-auth",
                            vcs.getAccessToken());
                    break;
                case GITLAB:
                    credentialsProvider = new UsernamePasswordCredentialsProvider("oauth2", vcs.getAccessToken());
                    break;
                case AZURE_DEVOPS:
                    credentialsProvider = new UsernamePasswordCredentialsProvider("dummy", vcs.getAccessToken());
                    break;
                case AZURE_SP_MI:
                    credentialsProvider = new UsernamePasswordCredentialsProvider("dummy", azDevOpsTokenService.getAzureDefaultToken());
                    break;
                default:
                    credentialsProvider = null;
                    break;
            }

            originalTags = Git.lsRemoteRepository()
                    .setTags(true)
                    .setRemote(source)
                    .setCredentialsProvider(credentialsProvider)
                    .setTimeout(GIT_TIMEOUT_SECONDS)
                    .callAsMap();
        }

        if (ssh != null) {
            log.info("vcs using ssh {}", ssh.getId());

            transportConfigCallback = transport -> {
                if (transport instanceof SshTransport sshTransport) {
                    TerrakubeSshdSessionFactory terrakubeSshdSessionFactory = TerrakubeSshdSessionFactory
                            .builder()
                            .sshId(ssh.getId().toString())
                            .sshFileName(ssh.getSshType().getFileName())
                            .privateKey(ssh.getPrivateKey())
                            .build();
                    sshTransport.setSshSessionFactory(terrakubeSshdSessionFactory.getSshdSessionFactory());
                    sshTransport.setTimeout(GIT_TIMEOUT_SECONDS);
                }
            };

            originalTags = Git.lsRemoteRepository()
                    .setTags(true)
                    .setRemote(source)
                    .setTransportConfigCallback(transportConfigCallback)
                    .setTimeout(GIT_TIMEOUT_SECONDS)
                    .callAsMap();
        }

        if (ssh == null && vcs == null) {
            originalTags = Git.lsRemoteRepository()
                    .setTags(true)
                    .setRemote(source)
                    .setTimeout(GIT_TIMEOUT_SECONDS)
                    .callAsMap();
        }

        originalTags.forEach((key, value) -> tags.put(key.replace("refs/tags/", ""), value));

        return tags;
    }
}
