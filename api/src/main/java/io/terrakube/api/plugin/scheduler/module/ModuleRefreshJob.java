package io.terrakube.api.plugin.scheduler.module;

import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

import io.terrakube.api.plugin.ssh.TerrakubeSshdSessionFactory;
import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ModuleVersionRepository;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.module.ModuleVersion;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsConnectionType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ModuleRefreshJob implements Job {
    @Autowired
    private ModuleRefreshService moduleRefreshService;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private ModuleRepository moduleRepository;
    @Autowired
    private ModuleVersionRepository moduleVersionRepository;

    @Override
    @Transactional
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String moduleId = context.getJobDetail().getJobDataMap().getString(moduleRefreshService.getJobDataKey());
        Optional<Module> search = moduleRepository.findById(UUID.fromString(moduleId));
        if (search.isEmpty())
            return;
        Module module = search.get();
        log.info("Refreshing module {} on {}", module.getName(), module.getOrganization().getName());
        Map<String, Ref> versions = null;

        try {
            versions = getVersionFromRepository(module.getSource(), module.getTagPrefix(),
                    module.getVcs(), module.getSsh());
        } catch (Exception e) {
            log.error("Failed to refresh module {} on organization/user {}, error {}", module.getName(),
                    module.getOrganization().getName(), e);
        }

        if (versions == null) {
            log.error("There are no tags available for module {} on organization/user {}, error {}", module.getName(),
                    module.getOrganization().getName(), "No versions found");
            return;
        }

        List<ModuleVersion> moduleVersions = new ArrayList<>();
        versions.forEach((key, value) -> {
            ModuleVersion moduleVersion = new ModuleVersion();
            moduleVersion.setVersion(key);
            moduleVersion.setCommit(value.getObjectId().getName());
            moduleVersion.setModule(module);
            moduleVersions.add(moduleVersion);
        });

        moduleVersionRepository.deleteByModuleId(module.getId());
        moduleVersionRepository.saveAll(moduleVersions);
    }

    private Map<String, Ref> getVersionFromRepository(String source, String tagPrefix, Vcs vcs, Ssh ssh)
            throws JsonMappingException, JsonProcessingException, NoSuchAlgorithmException, InvalidKeySpecException,
            URISyntaxException, InvalidRemoteException, TransportException, GitAPIException {
        List<String> versionList = new ArrayList<>();

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
                default:
                    credentialsProvider = null;
                    break;
            }

            originalTags = Git.lsRemoteRepository()
                    .setTags(true)
                    .setRemote(source)
                    .setCredentialsProvider(credentialsProvider)
                    .callAsMap();
        }

        if (ssh != null) {
            log.info("vcs using ssh {}", ssh.getId());

            transportConfigCallback = transport -> {
                if (transport instanceof SshTransport) {
                    if (transport instanceof SshTransport) {
                        TerrakubeSshdSessionFactory terrakubeSshdSessionFactory = TerrakubeSshdSessionFactory
                                .builder()
                                .sshId(ssh.getId().toString())
                                .sshFileName(ssh.getSshType().getFileName())
                                .privateKey(ssh.getPrivateKey())
                                .build();
                        ((SshTransport) transport)
                                .setSshSessionFactory(terrakubeSshdSessionFactory.getSshdSessionFactory());
                    }
                }
            };

            originalTags = Git.lsRemoteRepository()
                    .setTags(true)
                    .setRemote(source)
                    .setTransportConfigCallback(transportConfigCallback)
                    .callAsMap();
        }

        if (ssh == null && vcs == null) {
            originalTags = Git.lsRemoteRepository()
                    .setTags(true)
                    .setRemote(source)
                    .callAsMap();
        }

        originalTags.forEach((key, value) -> {
            String originalTag = key.replace("refs/tags/", "");
            if (tagPrefix == null) {
                tags.put(originalTag, value);
                versionList.add(originalTag);
            } else if (originalTag.startsWith(tagPrefix)) {
                tags.put(originalTag.replace(tagPrefix, ""), value);
            }
        });

        return tags;
    }
}