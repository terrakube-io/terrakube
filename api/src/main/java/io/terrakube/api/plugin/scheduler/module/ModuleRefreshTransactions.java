package io.terrakube.api.plugin.scheduler.module;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.eclipse.jgit.lib.Ref;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.terrakube.api.plugin.vcs.RepoUrlNormalizer;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ModuleVersionRepository;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.module.ModuleVersion;
import io.terrakube.api.rs.vcs.VcsType;

import lombok.extern.slf4j.Slf4j;

// A separate bean for the same reason as RepoWebhookDeliveryTransactions: ModuleRefreshPollerJob is a
// Quartz Job, which Spring instantiates without an AOP proxy, and ModuleRefreshWorker must call these
// through a proxy so each write is its own transaction. Keeping them short is the point: ls-remote runs
// between loadTarget and the record* call with no transaction (and no pooled connection) held.
//
// Every date written to module is truncated to whole seconds: the result writes match the lease by
// equality, and MSSQL datetime does not keep milliseconds exactly.
@Slf4j
@Service
public class ModuleRefreshTransactions {

    static final int MAX_ERROR_LENGTH = 1024;
    // The connections whose shared repository webhook the API can register (RepoWebhookService). Bitbucket has
    // none, so its modules rely on polling.
    public static final Set<VcsType> WEBHOOK_VCS_TYPES =
            Set.of(VcsType.GITHUB, VcsType.GITLAB, VcsType.AZURE_DEVOPS, VcsType.AZURE_SP_MI);
    // scheme://user:secret@host: JGit messages quote the remote URL, which can carry credentials.
    private static final Pattern URL_USER_INFO = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\\s]+@");

    private final ModuleRepository moduleRepository;
    private final ModuleVersionRepository moduleVersionRepository;
    private final ModuleLatestVersion moduleLatestVersion;
    private final ModuleRefreshProperties moduleRefreshProperties;

    ModuleRefreshTransactions(ModuleRepository moduleRepository, ModuleVersionRepository moduleVersionRepository,
            ModuleLatestVersion moduleLatestVersion, ModuleRefreshProperties moduleRefreshProperties) {
        this.moduleRepository = moduleRepository;
        this.moduleVersionRepository = moduleVersionRepository;
        this.moduleLatestVersion = moduleLatestVersion;
        this.moduleRefreshProperties = moduleRefreshProperties;
    }

    static String withoutUserInfo(String message) {
        return URL_USER_INFO.matcher(message).replaceAll("$1");
    }

    static Date toSeconds(long epochMillis) {
        return new Date(epochMillis - Math.floorMod(epochMillis, 1000L));
    }

    @Transactional
    public boolean claim(UUID id, Date lease, Date now) {
        return moduleRepository.claim(id, lease, now) == 1;
    }

    @Transactional
    public void releaseLeases(List<UUID> ids, Date lease) {
        moduleRepository.releaseLeases(ids, lease, toSeconds(System.currentTimeMillis()));
    }

    @Transactional
    public void markDue(UUID id) {
        moduleRepository.markDue(id);
    }

    /** Makes the modules of a repository due after a webhook reported a new tag. */
    @Transactional
    public int markDueBySource(String normalizedSource, String tag) {
        List<UUID> ids = findIdsByNormalizedSource(normalizedSource, tag);
        if (ids.isEmpty()) {
            return 0;
        }
        int count = moduleRepository.markDueIds(ids);
        log.info("Tag {} of {} made {} module(s) due for a refresh", tag, normalizedSource, count);
        return count;
    }

    /**
     * Modules whose source is the repository normalizedSource (see RepoUrlNormalizer) and whose tag prefix,
     * if any, the tag starts with. Other tags never become versions of the module.
     */
    // ponytail: reads every module per tag event, like WorkspaceRepository.findByNormalizedSourceWithMigratedWebhook.
    // Add a normalized_source column when module counts make this slow.
    private List<UUID> findIdsByNormalizedSource(String normalizedSource, String tag) {
        if (normalizedSource == null) {
            return List.of();
        }
        return moduleRepository.findIdSourceAndTagPrefix().stream()
                .filter(row -> normalizedSource.equalsIgnoreCase(RepoUrlNormalizer.normalize((String) row[1])))
                .filter(row -> row[2] == null || ((String) row[2]).isEmpty() || tag.startsWith((String) row[2]))
                .map(row -> (UUID) row[0])
                .toList();
    }

    public static boolean canRegisterWebhook(Module module) {
        return module.getVcs() != null && WEBHOOK_VCS_TYPES.contains(module.getVcs().getVcsType());
    }

    /**
     * Modules of the repository normalizedSource that need its webhook, when module webhooks are enabled. Runs
     * in the caller's transaction, which keeps using the returned entities.
     */
    public List<Module> findWebhookEligibleBySource(String normalizedSource) {
        if (normalizedSource == null) {
            return List.of();
        }
        return moduleRepository.findAllWithVcsTypeIn(WEBHOOK_VCS_TYPES).stream()
                .filter(m -> normalizedSource.equalsIgnoreCase(RepoUrlNormalizer.normalize(m.getSource())))
                .toList();
    }

    /** @return null when the module or its organization is gone, so there is nothing to refresh */
    @Transactional(readOnly = true)
    public ModuleRefreshTarget loadTarget(UUID id) {
        Module module = moduleRepository.findById(id).orElse(null);
        if (module == null || module.getOrganization() == null || module.getOrganization().isDisabled()) {
            return null;
        }
        // Read after this transaction has closed, so they must not stay lazy proxies.
        Hibernate.initialize(module.getVcs());
        Hibernate.initialize(module.getSsh());
        return new ModuleRefreshTarget(module.getId(), module.getName(), module.getOrganization().getName(),
                module.getSource(), module.getTagPrefix(), module.getVcs(), module.getSsh());
    }

    /**
     * Inserts the versions that are new and schedules the next refresh. Writes nothing when the lease is no
     * longer ours: the refresh outlived it, or the module was synced or changed meanwhile and claimed again.
     */
    @Transactional
    public void recordSuccess(UUID id, Date lease, Map<String, ModuleVersionNormalizer.NormalizedVersion> canonicalVersions,
            Map<String, Ref> tags) {
        Date now = toSeconds(System.currentTimeMillis());
        if (moduleRepository.markRefreshed(id, lease, now) == 0) {
            log.warn("Discarding stale refresh result for module {} - it was claimed again before this refresh finished",
                    id);
            return;
        }
        Module module = moduleRepository.findById(id).orElse(null);
        if (module == null) {
            return;
        }

        List<ModuleVersion> currentVersions = Optional.ofNullable(moduleVersionRepository.findAllByModuleId(id))
                .orElse(List.of());
        Set<String> knownVersions = currentVersions.stream().map(ModuleVersion::getVersion).collect(Collectors.toSet());
        List<ModuleVersion> newVersions = new ArrayList<>();
        canonicalVersions.forEach((canonicalVersion, normalized) -> {
            if (knownVersions.contains(canonicalVersion)) {
                return;
            }
            Ref ref = tags.get(normalized.originalTag());
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
            newVersions.add(moduleVersion);
            log.info("Adding new version {} (tag {}) to module {}", canonicalVersion, normalized.originalTag(),
                    module.getName());
        });

        List<ModuleVersion> allVersions = new ArrayList<>(currentVersions);
        if (newVersions.isEmpty()) {
            log.info("No new versions found for module {}", module.getName());
        } else {
            moduleVersionRepository.saveAll(newVersions).forEach(allVersions::add);
        }

        Date lastTagDetectedAt = newVersions.isEmpty() ? module.getLastTagDetectedAt() : now;
        moduleRepository.finishRefresh(id, moduleRefreshProperties.nextRefresh(now, lastTagDetectedAt, false,
                ThreadLocalRandom.current()::nextDouble), lastTagDetectedAt);
        moduleLatestVersion.recalculate(module, allVersions);
    }

    @Transactional
    public void recordFailure(UUID id, Date lease, String error) {
        Date now = toSeconds(System.currentTimeMillis());
        String cleaned = withoutUserInfo(error);
        String message = cleaned.length() <= MAX_ERROR_LENGTH ? cleaned : cleaned.substring(0, MAX_ERROR_LENGTH);
        Date next = moduleRefreshProperties.nextRefresh(now, null, true, ThreadLocalRandom.current()::nextDouble);
        if (moduleRepository.recordFailure(id, lease, next, now, message) == 0) {
            log.warn("Discarding stale refresh failure for module {} - it was claimed again before this refresh finished",
                    id);
        }
    }
}
