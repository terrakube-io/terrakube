package io.terrakube.executor.service.mode.batch;

import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.configuration.security.EncryptionService;
import io.terrakube.executor.configuration.security.InternalSecretLoader;
import io.terrakube.executor.service.executor.ExecutorJob;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.shutdown.ShutdownServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchModeServiceImplTest {

    @Mock
    private ExecutorJob executorJob;

    @Mock
    private ShutdownServiceImpl shutdownService;

    private ExecutorFlagsProperties flagsProperties;
    private EncryptionService encryptionService;
    private BatchModeServiceImpl batchModeService;

    private final String secretKey = "test-internal-secret-for-batch-mode";

    @BeforeEach
    void setUp() {
        flagsProperties = new ExecutorFlagsProperties();
        InternalSecretLoader secretLoader = new InternalSecretLoader(secretKey, null);
        encryptionService = new EncryptionService(secretLoader);
        batchModeService = new BatchModeServiceImpl(executorJob, flagsProperties, encryptionService, shutdownService);
    }

    @Test
    void onApplicationEvent_whenEphemeralTrue_decryptsAndCreatesJob() {
        flagsProperties.setEphemeral(true);

        String jobJson = "{\"jobId\":\"123\",\"organizationId\":\"org-1\",\"workspaceId\":\"ws-1\",\"branch\":\"main\"}";
        String encryptedJobJson = encryptionService.encrypt(jobJson);
        String base64Payload = Base64.getEncoder().encodeToString(encryptedJobJson.getBytes(StandardCharsets.UTF_8));
        flagsProperties.setEphemeralJobData(base64Payload);

        ContextRefreshedEvent event = mock(ContextRefreshedEvent.class);
        batchModeService.onApplicationEvent(event);

        ArgumentCaptor<TerraformJob> jobCaptor = ArgumentCaptor.forClass(TerraformJob.class);
        verify(executorJob, times(1)).createJob(jobCaptor.capture());
        verifyNoInteractions(shutdownService);

        TerraformJob createdJob = jobCaptor.getValue();
        assertNotNull(createdJob);
        assertEquals("123", createdJob.getJobId());
        assertEquals("org-1", createdJob.getOrganizationId());
        assertEquals("ws-1", createdJob.getWorkspaceId());
        assertEquals("main", createdJob.getBranch());
    }

    @Test
    void onApplicationEvent_whenEphemeralFalse_doesNothing() {
        flagsProperties.setEphemeral(false);
        flagsProperties.setEphemeralJobData("any-data");

        ContextRefreshedEvent event = mock(ContextRefreshedEvent.class);
        batchModeService.onApplicationEvent(event);

        verifyNoInteractions(executorJob);
        verifyNoInteractions(shutdownService);
    }

    @Test
    void onApplicationEvent_whenPayloadIsPlaintextUnencrypted_failsClosedAndDoesNotCreateJob() {
        flagsProperties.setEphemeral(true);

        // Raw unencrypted JSON base64-encoded (legacy/vulnerable format)
        String rawJson = "{\"jobId\":\"123\",\"organizationId\":\"org-1\"}";
        String base64Plaintext = Base64.getEncoder().encodeToString(rawJson.getBytes(StandardCharsets.UTF_8));
        flagsProperties.setEphemeralJobData(base64Plaintext);

        ContextRefreshedEvent event = mock(ContextRefreshedEvent.class);
        batchModeService.onApplicationEvent(event);

        verifyNoInteractions(executorJob);
        verify(shutdownService, times(1)).shutdownApplication(1);
        verify(shutdownService, never()).shutdownApplication();
    }

    @Test
    void onApplicationEvent_whenEncryptedWithDifferentSecret_failsClosedAndDoesNotCreateJob() {
        flagsProperties.setEphemeral(true);

        InternalSecretLoader otherLoader = new InternalSecretLoader("wrong-secret-key", null);
        EncryptionService otherEncryptionService = new EncryptionService(otherLoader);

        String jobJson = "{\"jobId\":\"999\"}";
        String encryptedWithWrongSecret = otherEncryptionService.encrypt(jobJson);
        String base64Payload = Base64.getEncoder().encodeToString(encryptedWithWrongSecret.getBytes(StandardCharsets.UTF_8));
        flagsProperties.setEphemeralJobData(base64Payload);

        ContextRefreshedEvent event = mock(ContextRefreshedEvent.class);
        batchModeService.onApplicationEvent(event);

        verifyNoInteractions(executorJob);
        verify(shutdownService, times(1)).shutdownApplication(1);
        verify(shutdownService, never()).shutdownApplication();
    }

    @Test
    void onApplicationEvent_whenPayloadIsInvalidJson_failsClosedAndDoesNotCreateJob() {
        flagsProperties.setEphemeral(true);

        String invalidJson = "this is not valid json";
        String encrypted = encryptionService.encrypt(invalidJson);
        String base64Payload = Base64.getEncoder().encodeToString(encrypted.getBytes(StandardCharsets.UTF_8));
        flagsProperties.setEphemeralJobData(base64Payload);

        ContextRefreshedEvent event = mock(ContextRefreshedEvent.class);
        batchModeService.onApplicationEvent(event);

        verifyNoInteractions(executorJob);
        verify(shutdownService, times(1)).shutdownApplication(1);
        verify(shutdownService, never()).shutdownApplication();
    }

    @Test
    void onApplicationEvent_whenDecryptionFails_invokesShutdownServiceWithExitCode1WithoutClosingContext() {
        flagsProperties.setEphemeral(true);
        flagsProperties.setEphemeralJobData(Base64.getEncoder().encodeToString("not-encrypted".getBytes(StandardCharsets.UTF_8)));

        ShutdownServiceImpl realShutdownService = new ShutdownServiceImpl();
        ConfigurableApplicationContext mockContext = mock(ConfigurableApplicationContext.class);
        realShutdownService.setApplicationContext(mockContext);

        AtomicInteger capturedExitCode = new AtomicInteger(-1);
        realShutdownService.setExitHandler(capturedExitCode::set);

        BatchModeServiceImpl service = new BatchModeServiceImpl(executorJob, flagsProperties, encryptionService, realShutdownService);

        ContextRefreshedEvent event = mock(ContextRefreshedEvent.class);
        service.onApplicationEvent(event);

        assertEquals(1, capturedExitCode.get(), "Must deliberately exit with code 1");
        verify(mockContext, never()).close();
    }
}
