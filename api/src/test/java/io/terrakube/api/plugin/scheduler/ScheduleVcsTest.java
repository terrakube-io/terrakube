package io.terrakube.api.plugin.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.mockito.junit.jupiter.MockitoExtension;

import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsStatus;
import io.terrakube.api.rs.vcs.VcsType;

@ExtendWith(MockitoExtension.class)
class ScheduleVcsTest {

    TokenService tokenService;
    VcsRepository vcsRepository;

    @BeforeEach
    void setup() {
        tokenService = mock(TokenService.class);
        vcsRepository = mock(VcsRepository.class);
    }

    @Test
    void failedRefreshMarksCompletedVcsAsError() throws Exception {
        UUID vcsId = UUID.randomUUID();
        Vcs vcs = vcs(vcsId, VcsStatus.COMPLETED);
        Vcs managedVcs = vcs(vcsId, VcsStatus.COMPLETED);

        doReturn(Optional.of(vcs)).when(vcsRepository).findById(vcsId);
        doReturn(Collections.emptyMap()).when(tokenService).refreshAccessToken(
                eq(vcsId.toString()),
                eq(vcs.getVcsType()),
                eq(vcs.getTokenExpiration()),
                eq(vcs.getClientId()),
                eq(vcs.getClientSecret()),
                eq(vcs.getRefreshToken()),
                eq(vcs.getCallback()),
                eq(vcs.getEndpoint()));
        doReturn(managedVcs).when(vcsRepository).getReferenceById(vcsId);
        doReturn(managedVcs).when(vcsRepository).save(managedVcs);

        subject().execute(jobExecutionContext(vcsId));

        assertThat(managedVcs.getStatus()).isEqualTo(VcsStatus.ERROR);
        verify(vcsRepository).save(managedVcs);
    }

    @Test
    void successfulRefreshUpdatesTokensAndKeepsCompletedStatus() throws Exception {
        UUID vcsId = UUID.randomUUID();
        Date newExpiration = new Date(System.currentTimeMillis() + 7_200_000);
        Vcs vcs = vcs(vcsId, VcsStatus.COMPLETED);
        Vcs managedVcs = vcs(vcsId, VcsStatus.COMPLETED);
        Map<String, Object> tokenInformation = Map.of(
                "accessToken", "new-access-token",
                "refreshToken", "new-refresh-token",
                "tokenExpiration", newExpiration);

        doReturn(Optional.of(vcs)).when(vcsRepository).findById(vcsId);
        doReturn(tokenInformation).when(tokenService).refreshAccessToken(
                eq(vcsId.toString()),
                eq(vcs.getVcsType()),
                eq(vcs.getTokenExpiration()),
                eq(vcs.getClientId()),
                eq(vcs.getClientSecret()),
                eq(vcs.getRefreshToken()),
                eq(vcs.getCallback()),
                eq(vcs.getEndpoint()));
        doReturn(managedVcs).when(vcsRepository).getReferenceById(vcsId);
        doReturn(managedVcs).when(vcsRepository).save(managedVcs);

        subject().execute(jobExecutionContext(vcsId));

        assertThat(managedVcs.getAccessToken()).isEqualTo("new-access-token");
        assertThat(managedVcs.getRefreshToken()).isEqualTo("new-refresh-token");
        assertThat(managedVcs.getTokenExpiration()).isEqualTo(newExpiration);
        assertThat(managedVcs.getStatus()).isEqualTo(VcsStatus.COMPLETED);
        verify(vcsRepository).save(managedVcs);
    }

    @Test
    void refreshRunsOnlyForCompletedConnections() throws Exception {
        for (VcsStatus status : new VcsStatus[] { VcsStatus.PENDING, VcsStatus.ERROR }) {
            UUID vcsId = UUID.randomUUID();
            doReturn(Optional.of(vcs(vcsId, status))).when(vcsRepository).findById(vcsId);

            subject().execute(jobExecutionContext(vcsId));
        }

        verifyNoInteractions(tokenService);
        verify(vcsRepository, never()).getReferenceById(any());
        verify(vcsRepository, never()).save(any());
    }

    private ScheduleVcs subject() {
        return new ScheduleVcs(tokenService, vcsRepository, null, null);
    }

    private Vcs vcs(UUID id, VcsStatus status) {
        Vcs vcs = new Vcs();
        vcs.setId(id);
        vcs.setVcsType(VcsType.GITLAB);
        vcs.setStatus(status);
        vcs.setClientId("client-id");
        vcs.setClientSecret("client-secret");
        vcs.setRefreshToken("refresh-token");
        vcs.setCallback("callback");
        vcs.setEndpoint("https://gitlab.example.com");
        vcs.setTokenExpiration(new Date(System.currentTimeMillis() - 1_000));
        return vcs;
    }

    private JobExecutionContext jobExecutionContext(UUID vcsId) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.put(ScheduleVcs.VCS_ID, vcsId.toString());

        JobDetail jobDetail = mock(JobDetail.class);
        doReturn(jobDataMap).when(jobDetail).getJobDataMap();

        JobExecutionContext jobExecutionContext = mock(JobExecutionContext.class);
        doReturn(jobDetail).when(jobExecutionContext).getJobDetail();
        return jobExecutionContext;
    }
}
