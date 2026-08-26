package io.terrakube.api.plugin.scheduler.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.repository.ProviderImplementationRepository;
import io.terrakube.api.repository.ProviderRepository;
import io.terrakube.api.repository.ProviderVersionRepository;
import io.terrakube.api.rs.provider.Provider;
import io.terrakube.api.rs.provider.implementation.Implementation;
import io.terrakube.api.rs.provider.implementation.Version;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderRefreshJobTest {

    @Test
    void preservesFullTrustSignature() throws Exception {
        UUID providerId = UUID.randomUUID();
        String trustSignature = "-----BEGIN PGP SIGNATURE-----" + "a".repeat(80) + "-----END PGP SIGNATURE-----";
        ObjectMapper objectMapper = new ObjectMapper();
        String versionsResponse = objectMapper.writeValueAsString(Map.of(
                "versions", List.of(Map.of(
                        "version", "0.0.22",
                        "protocols", List.of("5.0"),
                        "platforms", List.of(Map.of("os", "linux", "arch", "amd64"))
                ))
        ));
        String downloadResponse = objectMapper.writeValueAsString(Map.of(
                "os", "linux",
                "arch", "amd64",
                "signing_keys", Map.of("gpg_public_keys", List.of(Map.of("trust_signature", trustSignature)))
        ));
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .body(request.url().getPath().endsWith("/versions") ? versionsResponse : downloadResponse)
                        .build()))
                .build();
        ProviderRepository providerRepository = mock(ProviderRepository.class);
        ProviderVersionRepository versionRepository = mock(ProviderVersionRepository.class);
        ProviderImplementationRepository implementationRepository = mock(ProviderImplementationRepository.class);
        Provider provider = mock(Provider.class);
        when(provider.getId()).thenReturn(providerId);
        when(provider.getName()).thenReturn("coderd");
        when(provider.getRegistryNamespace()).thenReturn("coder");
        when(provider.isImported()).thenReturn(true);
        when(providerRepository.findById(providerId)).thenReturn(Optional.of(provider));
        when(versionRepository.findAllByProviderId(providerId)).thenReturn(List.of());
        when(versionRepository.save(any(Version.class))).thenAnswer(invocation -> invocation.getArgument(0));
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDetail jobDetail = mock(JobDetail.class);
        when(context.getJobDetail()).thenReturn(jobDetail);
        when(jobDetail.getJobDataMap()).thenReturn(new JobDataMap(Map.of("providerId", providerId.toString())));

        ProviderRefreshJob job = new ProviderRefreshJob();
        ReflectionTestUtils.setField(job, "webClient", webClient);
        ReflectionTestUtils.setField(job, "providerRepository", providerRepository);
        ReflectionTestUtils.setField(job, "providerVersionRepository", versionRepository);
        ReflectionTestUtils.setField(job, "providerImplementationRepository", implementationRepository);
        job.execute(context);

        ArgumentCaptor<Implementation> implementation = ArgumentCaptor.forClass(Implementation.class);
        verify(implementationRepository).save(implementation.capture());
        assertThat(implementation.getValue().getTrustSignature()).isEqualTo(trustSignature);
    }
}
