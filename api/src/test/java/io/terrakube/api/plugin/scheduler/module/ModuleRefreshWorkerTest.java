package io.terrakube.api.plugin.scheduler.module;

import io.terrakube.api.plugin.vcs.provider.exception.VcsTokenAcquisitionException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Ref;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.SocketTimeoutException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModuleRefreshWorkerTest {

    private final Ref dummyRef = mock(Ref.class);
    private final ModuleRefreshTransactions transactions = mock(ModuleRefreshTransactions.class);
    private final ModuleRefreshMetrics metrics = mock(ModuleRefreshMetrics.class);
    private final ModuleRefreshWorker worker = new ModuleRefreshWorker(transactions, metrics, null, null);

    @Test
    void publishesEachNonCollidingTagUnderItsCanonicalVersion() {
        Map<String, Ref> rawRepoTags = new HashMap<>();
        rawRepoTags.put("v2.0.1", dummyRef);
        rawRepoTags.put("2.0.2", dummyRef);
        rawRepoTags.put("latest", dummyRef);
        rawRepoTags.put("v2", dummyRef);

        Map<String, ModuleVersionNormalizer.NormalizedVersion> result =
                worker.resolveCanonicalVersions(rawRepoTags, null, "test-module");

        assertThat(result).hasSize(2);
        assertThat(result.get("2.0.1").originalTag()).isEqualTo("v2.0.1");
        assertThat(result.get("2.0.2").originalTag()).isEqualTo("2.0.2");
    }

    @Test
    void collidingTagsAreExcludedFromTheResult() {
        Map<String, Ref> rawRepoTags = new HashMap<>();
        rawRepoTags.put("v2.0.1", dummyRef);
        rawRepoTags.put("2.0.1", dummyRef);
        rawRepoTags.put("2.0.2", dummyRef);

        Map<String, ModuleVersionNormalizer.NormalizedVersion> result =
                worker.resolveCanonicalVersions(rawRepoTags, null, "test-module");

        assertThat(result).containsOnlyKeys("2.0.2");
    }

    @Test
    void tagPrefixIsRespected() {
        Map<String, Ref> rawRepoTags = new HashMap<>();
        rawRepoTags.put("module-v2.0.1", dummyRef);
        rawRepoTags.put("other-v9.9.9", dummyRef);

        Map<String, ModuleVersionNormalizer.NormalizedVersion> result =
                worker.resolveCanonicalVersions(rawRepoTags, "module-", "test-module");

        assertThat(result).containsOnlyKeys("2.0.1");
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new TransportException("https://github.com/acme/private.git: not authorized"), "auth"),
                Arguments.of(new TransportException("git@github.com:acme/x.git: No more Authentication methods available"), "auth"),
                Arguments.of(new VcsTokenAcquisitionException("no installation token", null, false), "auth"),
                Arguments.of(new TransportException("https://github.com/acme/gone.git: Repository not found"), "not_found"),
                Arguments.of(new TransportException("https://git.example.com/x.git: cannot open git-upload-pack",
                        new SocketTimeoutException("Read timed out")), "timeout"),
                Arguments.of(new TransportException("https://git.example.com/x.git: Connection timed out"), "timeout"),
                Arguments.of(new IllegalStateException("boom"), "other"),
                Arguments.of(new IllegalStateException(), "other"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void classifiesFailureCauses(Exception failure, String cause) {
        assertThat(ModuleRefreshWorker.classify(failure)).isEqualTo(cause);
    }

    @Test
    void failedReadIsRecordedWithItsCauseUnderTheLease() throws Exception {
        UUID id = UUID.randomUUID();
        Date lease = new Date();
        when(transactions.loadTarget(id)).thenReturn(target(id));
        when(metrics.timeLsRemote(any())).thenThrow(new TransportException("https://x.test/r.git: not authorized"));

        worker.refresh(id, lease);

        verify(transactions).recordFailure(eq(id), eq(lease), startsWith("auth: "));
        verify(metrics).recordResult("failure", "auth");
        verify(transactions, never()).recordSuccess(any(), any(), anyMap(), anyMap());
    }

    @Test
    void repositoryWithoutTagsIsAFailure() throws Exception {
        UUID id = UUID.randomUUID();
        Date lease = new Date();
        when(transactions.loadTarget(id)).thenReturn(target(id));
        when(metrics.timeLsRemote(any())).thenReturn(Map.of());

        worker.refresh(id, lease);

        verify(transactions).recordFailure(eq(id), eq(lease), startsWith("no_tags: "));
        verify(transactions, never()).recordSuccess(any(), any(), anyMap(), anyMap());
    }

    @Test
    void tagsAreRecordedAsSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        Date lease = new Date();
        Map<String, Ref> tags = Map.of("v1.2.0", dummyRef);
        when(transactions.loadTarget(id)).thenReturn(target(id));
        when(metrics.timeLsRemote(any())).thenReturn(tags);

        worker.refresh(id, lease);

        verify(transactions).recordSuccess(eq(id), eq(lease),
                eq(Map.of("1.2.0", new ModuleVersionNormalizer.NormalizedVersion("1.2.0", "v1.2.0"))), eq(tags));
        verify(metrics).recordResult("success", "none");
    }

    @Test
    void goneModuleIsSkipped() throws Exception {
        UUID id = UUID.randomUUID();

        worker.refresh(id, new Date());

        verify(metrics, never()).timeLsRemote(any());
        verify(transactions, never()).recordFailure(any(), any(), any());
    }

    private static ModuleRefreshTarget target(UUID id) {
        return new ModuleRefreshTarget(id, "network", "acme", "https://x.test/r.git", null, null, null);
    }
}
