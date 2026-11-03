package io.terrakube.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.terrakube.api.plugin.scheduler.module.ModuleRefreshPollerScheduler;
import io.terrakube.api.plugin.scheduler.module.ModuleRefreshTransactions;
import io.terrakube.api.plugin.scheduler.module.ModuleVersionNormalizer.NormalizedVersion;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.repository.ModuleVersionRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.module.Module;
import io.terrakube.api.rs.module.ModuleVersion;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleRefreshTransactionsIntegrationTest extends ServerApplicationTests {

    private static final UUID DEMO_ORGANIZATION_ID = UUID.fromString("f5365c9e-bc11-4781-b649-45a281ccdd4a");
    private static final JobKey POLLER = new JobKey(ModuleRefreshPollerScheduler.PREFIX_MODULE_REFRESH_POLLER);

    @Autowired
    ModuleRefreshTransactions transactions;

    @Autowired
    ModuleRepository moduleRepository;

    @Autowired
    ModuleVersionRepository moduleVersionRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    // The real poller would claim the modules seeded here between the steps of a test.
    @BeforeAll
    void pausePoller() throws SchedulerException {
        scheduler.pauseJob(POLLER);
    }

    @AfterAll
    void resumePoller() throws SchedulerException {
        scheduler.resumeJob(POLLER);
    }

    private static Date nowSeconds() {
        return Date.from(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private static Date plusMinutes(Date date, long minutes) {
        return new Date(date.getTime() + minutes * 60_000L);
    }

    private Module seed(Organization organization, Date nextRefresh) {
        Module module = new Module();
        module.setName("refresh-" + UUID.randomUUID().toString().substring(0, 8));
        module.setProvider("aws");
        module.setSource("https://git.example.test/acme/network.git");
        module.setOrganization(organization);
        module.setNextRefresh(nextRefresh);
        return moduleRepository.save(module);
    }

    private Module seed(Date nextRefresh) {
        return seed(organizationRepository.findById(DEMO_ORGANIZATION_ID).orElseThrow(), nextRefresh);
    }

    private Module reload(Module module) {
        return moduleRepository.findById(module.getId()).orElseThrow();
    }

    private static Map<String, NormalizedVersion> canonical(String... tags) {
        return Arrays.stream(tags).collect(Collectors.toMap(
                tag -> tag.substring(1), tag -> new NormalizedVersion(tag.substring(1), tag)));
    }

    private static Map<String, Ref> refs(String... tags) {
        return Arrays.stream(tags).collect(Collectors.toMap(tag -> tag,
                tag -> new ObjectIdRef.PeeledNonTag(Ref.Storage.NETWORK, "refs/tags/" + tag,
                        ObjectId.fromString("0123456789abcdef0123456789abcdef01234567"))));
    }

    private static long secondsBetween(Date from, Date to) {
        return (to.getTime() - from.getTime()) / 1000;
    }

    @Test
    void secondClaimOfSameRowFails() {
        Module module = seed(null);
        Date now = nowSeconds();

        assertThat(transactions.claim(module.getId(), plusMinutes(now, 5), now)).isTrue();
        assertThat(transactions.claim(module.getId(), plusMinutes(now, 5), now)).isFalse();
        assertThat(reload(module).getNextRefresh().getTime()).isEqualTo(plusMinutes(now, 5).getTime());
    }

    @Test
    void staleWriterAfterLeaseReclaimWritesNothing() {
        Module module = seed(null);
        Date now = nowSeconds();
        Date staleLease = plusMinutes(now, 5);
        assertThat(transactions.claim(module.getId(), staleLease, now)).isTrue();
        // The first refresh outlives its lease and another tick claims the module again.
        Date later = plusMinutes(now, 6);
        Date currentLease = plusMinutes(later, 5);
        assertThat(transactions.claim(module.getId(), currentLease, later)).isTrue();

        transactions.recordSuccess(module.getId(), staleLease, canonical("v1.0.0"), refs("v1.0.0"));
        transactions.recordFailure(module.getId(), staleLease, "other: late");

        Module stored = reload(module);
        assertThat(stored.getNextRefresh().getTime()).isEqualTo(currentLease.getTime());
        assertThat(stored.getLastRefresh()).isNull();
        assertThat(stored.getLastRefreshError()).isNull();
        assertThat(moduleVersionRepository.findAllByModuleId(module.getId())).isEmpty();
    }

    @Test
    void nullNextRefreshIsOrderedFirst() {
        Date now = nowSeconds();
        Module overdue = seed(plusMinutes(now, -30));
        Module fresh = seed(null);

        List<UUID> due = moduleRepository.findDueIds(now, PageRequest.of(0, 1000));

        assertThat(due).contains(overdue.getId(), fresh.getId());
        assertThat(due.indexOf(fresh.getId())).isLessThan(due.indexOf(overdue.getId()));
        assertThat(moduleRepository.findAllById(due.subList(0, due.indexOf(fresh.getId()) + 1)))
                .allMatch(module -> module.getNextRefresh() == null);
    }

    @Test
    void notYetDueModulesAreNotSelectedOrClaimed() {
        Date now = nowSeconds();
        Module later = seed(plusMinutes(now, 10));

        assertThat(moduleRepository.findDueIds(now, PageRequest.of(0, 1000))).doesNotContain(later.getId());
        assertThat(transactions.claim(later.getId(), plusMinutes(now, 5), now)).isFalse();
    }

    @Test
    void disabledOrganizationModulesAreNeverDue() {
        Organization disabled = new Organization();
        disabled.setName("disabled-" + UUID.randomUUID().toString().substring(0, 8));
        disabled.setDisabled(true);
        disabled = organizationRepository.save(disabled);
        Module module = seed(disabled, null);

        assertThat(moduleRepository.findDueIds(nowSeconds(), PageRequest.of(0, 1000))).doesNotContain(module.getId());
        assertThat(transactions.loadTarget(module.getId())).isNull();
    }

    @Test
    void failureRecordsErrorAndSuccessClearsIt() {
        Module module = seed(null);
        Date now = nowSeconds();
        Date lease = plusMinutes(now, 5);
        transactions.claim(module.getId(), lease, now);

        transactions.recordFailure(module.getId(), lease, "auth: https://git.example.test/acme/network.git: not authorized");

        Module failed = reload(module);
        assertThat(failed.getLastRefreshError()).isEqualTo("auth: https://git.example.test/acme/network.git: not authorized");
        assertThat(secondsBetween(failed.getLastRefresh(), failed.getNextRefresh())).isBetween(51 * 60L, 69 * 60L);

        Date retryAt = new Date(failed.getNextRefresh().getTime() + 1000);
        Date retryLease = plusMinutes(retryAt, 5);
        assertThat(transactions.claim(module.getId(), retryLease, retryAt)).isTrue();
        transactions.recordSuccess(module.getId(), retryLease, canonical("v1.0.0"), refs("v1.0.0"));

        assertThat(reload(module).getLastRefreshError()).isNull();
    }

    @Test
    void longErrorIsTruncated() {
        Module module = seed(null);
        Date now = nowSeconds();
        transactions.claim(module.getId(), plusMinutes(now, 5), now);

        transactions.recordFailure(module.getId(), plusMinutes(now, 5), "other: " + "x".repeat(2000));

        assertThat(reload(module).getLastRefreshError()).hasSize(1024);
    }

    @Test
    void newVersionSetsLastTagDetectedAt() {
        Module module = seed(null);
        Date longAgo = Date.from(Instant.now().minus(200, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS));
        module.setLastTagDetectedAt(longAgo);
        module = moduleRepository.save(module);
        ModuleVersion existing = new ModuleVersion();
        existing.setModule(module);
        existing.setVersion("1.0.0");
        existing.setGitTag("v1.0.0");
        existing.setCommit("0123456789abcdef0123456789abcdef01234567");
        moduleVersionRepository.save(existing);

        // Nothing new: the tag age stays, so the module stays on the dormant interval.
        Date now = nowSeconds();
        transactions.claim(module.getId(), plusMinutes(now, 5), now);
        transactions.recordSuccess(module.getId(), plusMinutes(now, 5), canonical("v1.0.0"), refs("v1.0.0"));
        Module unchanged = reload(module);
        assertThat(unchanged.getLastTagDetectedAt().getTime()).isEqualTo(longAgo.getTime());
        assertThat(secondsBetween(unchanged.getLastRefresh(), unchanged.getNextRefresh())).isBetween(306 * 60L, 414 * 60L);

        Date retryAt = new Date(unchanged.getNextRefresh().getTime() + 1000);
        transactions.claim(module.getId(), plusMinutes(retryAt, 5), retryAt);
        transactions.recordSuccess(module.getId(), plusMinutes(retryAt, 5), canonical("v1.0.0", "v1.1.0"),
                refs("v1.0.0", "v1.1.0"));

        Module tagged = reload(module);
        assertThat(tagged.getLastTagDetectedAt().getTime()).isEqualTo(tagged.getLastRefresh().getTime());
        assertThat(moduleVersionRepository.findAllByModuleId(module.getId())).extracting(ModuleVersion::getVersion)
                .containsExactlyInAnyOrder("1.0.0", "1.1.0");
    }

    @Test
    void successThatChangesLatestVersionKeepsTheComputedNextRefresh() {
        Module module = seed(null);
        Date now = nowSeconds();
        Date lease = plusMinutes(now, 5);
        transactions.claim(module.getId(), lease, now);

        // A new version also moves latest_version, which ModuleLatestVersion saves through the entity.
        transactions.recordSuccess(module.getId(), lease, canonical("v1.0.0"), refs("v1.0.0"));

        Module stored = reload(module);
        assertThat(stored.getLatestVersion()).isEqualTo("1.0.0");
        assertThat(stored.getNextRefresh().getTime()).isNotEqualTo(lease.getTime());
        // A tag was just detected, so the active interval (15 min) applies, moved by at most 15 %.
        assertThat(secondsBetween(stored.getLastRefresh(), stored.getNextRefresh())).isBetween(765L, 1035L);
        assertThat(stored.getNextRefresh().getTime() % 1000).isZero();
    }

    @Test
    void entityUpdateAfterClaimKeepsTheLease() {
        Module module = seed(null);
        Date now = nowSeconds();
        Date lease = plusMinutes(now, 5);
        TransactionTemplate request = new TransactionTemplate(transactionManager);
        TransactionTemplate poller = new TransactionTemplate(transactionManager);
        poller.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // An update that loaded the module before the poller claimed it, like an API PATCH.
        request.executeWithoutResult(status -> {
            Module loaded = moduleRepository.findById(module.getId()).orElseThrow();
            poller.executeWithoutResult(claim -> assertThat(transactions.claim(module.getId(), lease, now)).isTrue());
            loaded.setDescription("changed while claimed");
        });

        Module stored = reload(module);
        assertThat(stored.getDescription()).isEqualTo("changed while claimed");
        assertThat(stored.getNextRefresh().getTime()).isEqualTo(lease.getTime());
    }

    @Test
    void markDueMakesTheModuleDueNow() {
        Date now = nowSeconds();
        Module module = seed(plusMinutes(now, 60));

        transactions.markDue(module.getId());

        assertThat(reload(module).getNextRefresh()).isNull();
        assertThat(moduleRepository.findDueIds(now, PageRequest.of(0, 1000))).contains(module.getId());
    }

    @Test
    void releasedLeaseIsDueAgain() {
        Module module = seed(null);
        Date now = nowSeconds();
        Date lease = plusMinutes(now, 5);
        transactions.claim(module.getId(), lease, now);

        transactions.releaseLeases(List.of(module.getId()), lease);

        Module stored = reload(module);
        assertThat(stored.getNextRefresh().getTime()).isLessThanOrEqualTo(System.currentTimeMillis());
        assertThat(moduleRepository.findDueIds(nowSeconds(), PageRequest.of(0, 1000))).contains(module.getId());
    }

    private Module seedSource(String source, String tagPrefix, Date nextRefresh) {
        Module module = seed(nextRefresh);
        module.setSource(source);
        module.setTagPrefix(tagPrefix);
        return moduleRepository.save(module);
    }

    @Test
    void markDueBySourceMatchesTheNormalizedRepositoryUrl() {
        String repo = "https://github.com/acme/net-" + UUID.randomUUID().toString().substring(0, 8);
        Date later = plusMinutes(nowSeconds(), 60);
        Module plain = seedSource(repo + ".git", null, later);
        Module upperCase = seedSource(repo.toUpperCase().replace("HTTPS", "https"), null, later);
        Module withUser = seedSource(repo.replace("https://", "https://user@") + "/", null, later);
        Module other = seedSource(repo + "-other.git", null, later);

        assertThat(transactions.markDueBySource(repo, "v1.0.0")).isEqualTo(3);

        assertThat(reload(plain).getNextRefresh()).isNull();
        assertThat(reload(upperCase).getNextRefresh()).isNull();
        assertThat(reload(withUser).getNextRefresh()).isNull();
        assertThat(reload(other).getNextRefresh().getTime()).isEqualTo(later.getTime());
    }

    @Test
    void markDueBySourceSkipsModulesWhoseTagPrefixDoesNotMatch() {
        String repo = "https://gitlab.com/acme/mono-" + UUID.randomUUID().toString().substring(0, 8);
        Date later = plusMinutes(nowSeconds(), 60);
        Module vpc = seedSource(repo, "vpc/", later);
        Module eks = seedSource(repo, "eks/", later);
        Module noPrefix = seedSource(repo, "", later);

        transactions.markDueBySource(repo, "vpc/v1.4.0");

        assertThat(reload(vpc).getNextRefresh()).isNull();
        assertThat(reload(noPrefix).getNextRefresh()).isNull();
        assertThat(reload(eks).getNextRefresh().getTime()).isEqualTo(later.getTime());
    }

    @Test
    void webhookEligibleModulesHaveAConnectionThatCanRegisterIt() {
        String repo = "https://github.com/acme/eligible-" + UUID.randomUUID().toString().substring(0, 8);
        Organization organization = organizationRepository.findById(DEMO_ORGANIZATION_ID).orElseThrow();
        Module github = seedSource(repo + ".git", null, null);
        github.setVcs(vcs(organization, VcsType.GITHUB));
        moduleRepository.save(github);
        Module bitbucket = seedSource(repo, null, null);
        bitbucket.setVcs(vcs(organization, VcsType.BITBUCKET));
        moduleRepository.save(bitbucket);
        seedSource(repo, null, null);

        assertThat(transactions.findWebhookEligibleBySource(repo)).extracting(Module::getId)
                .containsExactly(github.getId());
    }

    private Vcs vcs(Organization organization, VcsType type) {
        Vcs vcs = new Vcs();
        vcs.setName("module-webhook-" + type);
        vcs.setDescription("module webhook test");
        vcs.setVcsType(type);
        vcs.setOrganization(organization);
        return vcsRepository.saveAndFlush(vcs);
    }
}
