package io.terrakube.api.plugin.scheduler.module;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.terrakube.api.plugin.ssh.TerrakubeSshdSessionFactory;
import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.plugin.vcs.provider.azdevops.AzDevOpsTokenService;
import io.terrakube.api.plugin.vcs.provider.exception.VcsTokenAcquisitionException;
import io.terrakube.api.rs.ssh.Ssh;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsConnectionType;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.*;

/**
 * Refreshes one claimed module on a moduleRefreshExecutor thread: reads it in a short transaction, runs
 * one ls-remote with no transaction open, then records the result under the lease it was claimed with.
 */
@Slf4j
@Component
public class ModuleRefreshWorker {

    private static final int GIT_TIMEOUT_SECONDS = 30;

    private final ModuleRefreshTransactions moduleRefreshTransactions;
    private final ModuleRefreshMetrics moduleRefreshMetrics;
    private final TokenService tokenService;
    private final AzDevOpsTokenService azDevOpsTokenService;

    public ModuleRefreshWorker(ModuleRefreshTransactions moduleRefreshTransactions,
            ModuleRefreshMetrics moduleRefreshMetrics, TokenService tokenService,
            AzDevOpsTokenService azDevOpsTokenService) {
        this.moduleRefreshTransactions = moduleRefreshTransactions;
        this.moduleRefreshMetrics = moduleRefreshMetrics;
        this.tokenService = tokenService;
        this.azDevOpsTokenService = azDevOpsTokenService;
    }

    public void refresh(UUID moduleId, Date lease) {
        ModuleRefreshTarget module = moduleRefreshTransactions.loadTarget(moduleId);
        if (module == null) {
            log.info("Skipping refresh of module {}: it or its organization no longer exists", moduleId);
            return;
        }
        log.info("Refreshing module {} on {}", module.name(), module.organizationName());

        Map<String, Ref> rawRepoTags;
        try {
            rawRepoTags = moduleRefreshMetrics.timeLsRemote(
                    () -> getVersionFromRepository(module.source(), module.vcs(), module.ssh()));
        } catch (Exception e) {
            // Broad catch is intentional: any VCS/network failure is this module's result, not the pool's.
            String cause = classify(e);
            log.error("Failed to refresh module {} on organization/user {}, cause {}, error {}", module.name(),
                    module.organizationName(), cause, e.getMessage());
            recordFailure(moduleId, lease, cause, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return;
        }

        if (rawRepoTags.isEmpty()) {
            log.error("There are no tags available for module {} on organization/user {}", module.name(),
                    module.organizationName());
            recordFailure(moduleId, lease, "no_tags", "The repository has no tags.");
            return;
        }

        Map<String, ModuleVersionNormalizer.NormalizedVersion> canonicalVersions =
                resolveCanonicalVersions(rawRepoTags, module.tagPrefix(), module.name());
        moduleRefreshTransactions.recordSuccess(moduleId, lease, canonicalVersions, rawRepoTags);
        moduleRefreshMetrics.recordResult("success", "none");
    }

    private void recordFailure(UUID moduleId, Date lease, String cause, String message) {
        moduleRefreshMetrics.recordResult("failure", cause);
        moduleRefreshTransactions.recordFailure(moduleId, lease, cause + ": " + message);
    }

    /** Sorts a failed ls-remote into auth, not_found, timeout or other, from JGit's messages and causes. */
    static String classify(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SocketTimeoutException) {
                return "timeout";
            }
            if (current instanceof VcsTokenAcquisitionException) {
                return "auth";
            }
            messages.append(' ').append(current.getMessage());
        }
        String text = messages.toString().toLowerCase(Locale.ROOT);
        if (text.contains("not authorized") || text.contains("authentication")) {
            return "auth";
        }
        if (text.contains("not found")) {
            return "not_found";
        }
        if (text.contains("timed out") || text.contains("timeout")) {
            return "timeout";
        }
        return "other";
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

    private Map<String, Ref> getVersionFromRepository(String source, Vcs vcs, Ssh ssh)
            throws JsonProcessingException, NoSuchAlgorithmException, InvalidKeySpecException,
            URISyntaxException, GitAPIException, VcsTokenAcquisitionException {
        LsRemoteCommand lsRemote = Git.lsRemoteRepository()
                .setTags(true)
                .setRemote(source)
                .setTimeout(GIT_TIMEOUT_SECONDS);
        if (vcs != null) {
            log.info("vcs using {}", vcs.getVcsType().toString());
            lsRemote.setCredentialsProvider(getCredentialsProvider(source, vcs));
        }

        // Configuring both lets the transport the source URL selects pick what it needs, in one ls-remote.
        if (ssh != null) {
            log.info("vcs using ssh {}", ssh.getId());
            lsRemote.setTransportConfigCallback(transport -> {
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
            });
        }

        Map<String, Ref> tags = new HashMap<>();
        Map<String, Ref> originalTags = lsRemote.callAsMap();
        originalTags.forEach((key, value) -> tags.put(key.replace("refs/tags/", ""), value));

        return tags;
    }

    private CredentialsProvider getCredentialsProvider(String source, Vcs vcs)
            throws JsonProcessingException, NoSuchAlgorithmException, InvalidKeySpecException,
            URISyntaxException, VcsTokenAcquisitionException {
        return switch (vcs.getVcsType()) {
            case GITHUB -> vcs.getConnectionType() == VcsConnectionType.OAUTH
                    ? new UsernamePasswordCredentialsProvider(vcs.getAccessToken(), "")
                    : new UsernamePasswordCredentialsProvider("x-access-token", tokenService.getAccessToken(source, vcs));
            case BITBUCKET -> new UsernamePasswordCredentialsProvider("x-token-auth", vcs.getAccessToken());
            case GITLAB -> new UsernamePasswordCredentialsProvider("oauth2", vcs.getAccessToken());
            case AZURE_DEVOPS -> new UsernamePasswordCredentialsProvider("dummy", vcs.getAccessToken());
            case AZURE_SP_MI -> new UsernamePasswordCredentialsProvider("dummy", azDevOpsTokenService.getAzureDefaultToken());
            default -> null;
        };
    }
}
