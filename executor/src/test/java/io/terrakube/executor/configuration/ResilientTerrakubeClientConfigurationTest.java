package io.terrakube.executor.configuration;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.organization.Organization;
import io.terrakube.client.model.organization.job.JobRequest;
import io.terrakube.client.model.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResilientTerrakubeClientConfigurationTest {

    @Mock
    TerrakubeClient delegate;

    private TerrakubeClient subject() {
        return (TerrakubeClient) Proxy.newProxyInstance(
                TerrakubeClient.class.getClassLoader(),
                new Class<?>[] { TerrakubeClient.class },
                new ResilientTerrakubeClientConfiguration.RetryingInvocationHandler(delegate, 3, Duration.ofMillis(1)));
    }

    @Test
    void passesThroughOnFirstSuccessWithoutRetrying() {
        Response<List<Organization>> response = new Response<>();
        when(delegate.getAllOrganizations()).thenReturn(response);

        Response<List<Organization>> result = subject().getAllOrganizations();

        assertSame(response, result);
        verify(delegate, times(1)).getAllOrganizations();
    }

    @Test
    void retriesATransientFailureThenReturnsTheEventualSuccess() {
        Response<List<Organization>> response = new Response<>();
        when(delegate.getAllOrganizations())
                .thenThrow(new RuntimeException("connection refused"))
                .thenReturn(response);

        Response<List<Organization>> result = subject().getAllOrganizations();

        assertSame(response, result);
        verify(delegate, times(2)).getAllOrganizations();
    }

    @Test
    void throwsTheOriginalExceptionAfterExhaustingAllAttempts() {
        RuntimeException failure = new RuntimeException("connection refused");
        when(delegate.getAllOrganizations()).thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> subject().getAllOrganizations());

        assertSame(failure, thrown);
        verify(delegate, times(3)).getAllOrganizations();
    }

    @Test
    void retriesApplyGenericallyToVoidMethodsToo() {
        // Proves this isn't hardcoded to one method (e.g. getJobById) - the proxy retries
        // whatever TerrakubeClient method is called, including void ones like updateJob.
        doThrow(new RuntimeException("connection refused")).doNothing()
                .when(delegate).updateJob(any(JobRequest.class), anyString(), anyString());

        subject().updateJob(new JobRequest(), "org-1", "job-1");

        verify(delegate, times(2)).updateJob(any(JobRequest.class), anyString(), anyString());
    }
}
