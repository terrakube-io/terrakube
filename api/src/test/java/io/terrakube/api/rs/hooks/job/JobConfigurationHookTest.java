package io.terrakube.api.rs.hooks.job;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.exceptions.BadRequestException;
import io.terrakube.api.repository.ContentRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.content.Content;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobConfigurationHookTest {
    @Test
    void onlyBindsAnUploadedConfigurationFromTheSameWorkspaceWithARevision() {
        var contents = mock(ContentRepository.class);
        var hook = new JobConfigurationHook(contents);
        ReflectionTestUtils.setField(hook, "hostname", "api.example");
        var workspace = new Workspace();
        workspace.setId(UUID.randomUUID());
        var content = new Content();
        content.setId(UUID.randomUUID());
        content.setWorkspace(workspace);
        content.setStatus("uploaded");
        when(contents.findById(content.getId())).thenReturn(Optional.of(content));
        var job = new Job();
        job.setWorkspace(workspace);
        job.setCommitId("a".repeat(40));
        job.setConfigurationVersion(content.getId());
        hook.execute(Operation.CREATE, TransactionPhase.PREFLUSH, job, null, Optional.empty());
        assertEquals("remote-content", job.getOverrideBranch());
        assertEquals("https://api.example/remote/tfe/v2/configuration-versions/" + content.getId()
                + "/terraformContent.tar.gz", job.getOverrideSource());
        assertNull(job.getConfigurationVersion());
        for (String status : new String[]{"pending", "uploading", "failed"}) {
            content.setStatus(status);
            job.setConfigurationVersion(content.getId());
            assertThrows(BadRequestException.class,
                    () -> hook.execute(Operation.CREATE, TransactionPhase.PREFLUSH, job, null, Optional.empty()));
        }
        content.setStatus("uploaded");
        job.setWorkspace(new Workspace());
        assertThrows(BadRequestException.class,
                () -> hook.execute(Operation.CREATE, TransactionPhase.PREFLUSH, job, null, Optional.empty()));
        job.setWorkspace(workspace);
        job.setCommitId(null);
        assertThrows(BadRequestException.class,
                () -> hook.execute(Operation.CREATE, TransactionPhase.PREFLUSH, job, null, Optional.empty()));
        when(contents.findById(content.getId())).thenReturn(Optional.empty());
        assertThrows(BadRequestException.class,
                () -> hook.execute(Operation.CREATE, TransactionPhase.PREFLUSH, job, null, Optional.empty()));
    }

    @Test
    void gitJobsAndUpdatesDoNotResolveUploads() {
        var contents = mock(ContentRepository.class);
        var hook = new JobConfigurationHook(contents);
        var job = new Job();
        hook.execute(Operation.CREATE, TransactionPhase.PREFLUSH, job, null, Optional.empty());
        job.setConfigurationVersion(UUID.randomUUID());
        hook.execute(Operation.UPDATE, TransactionPhase.PREFLUSH, job, null, Optional.empty());
        verifyNoInteractions(contents);
    }
}
