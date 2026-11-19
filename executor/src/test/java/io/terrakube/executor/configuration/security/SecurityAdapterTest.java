package io.terrakube.executor.configuration.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.generic.Resource;
import io.terrakube.client.model.organization.job.Job;
import io.terrakube.client.model.organization.job.JobAttributes;
import io.terrakube.client.model.organization.job.OrganizationData;
import io.terrakube.client.model.organization.job.Relationships;
import io.terrakube.client.model.organization.job.WorkspaceData;
import io.terrakube.client.model.organization.job.step.Step;
import io.terrakube.client.model.response.ResponseWithInclude;
import io.terrakube.client.spring.autoconfigure.RestClientProperties;
import io.terrakube.executor.service.executor.ExecutorCapacityGate;
import io.terrakube.executor.service.executor.ExecutorJob;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.mode.online.OnlineModeServiceImpl;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurityImpl;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityAdapterTest {

    private static final String SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "terrakube-executor-security-adapter-test-secret-key-64-bytes-min!".getBytes(StandardCharsets.UTF_8));

    private static final String JOB_PAYLOAD =
            "{\"organizationId\":\"org-1\",\"workspaceId\":\"ws-1\",\"jobId\":\"job-1\",\"stepId\":\"step-1\",\"type\":\"terraformPlan\",\"branch\":\"main\"}";

    @Configuration
    @EnableWebMvc
    @Import(SecurityAdapter.class)
    static class TestWebConfig {
        @Bean
        InternalSecretLoader internalSecretLoader() {
            return new InternalSecretLoader(SECRET, null);
        }

        @Bean
        ExecutorJob executorJob() {
            return mock(ExecutorJob.class);
        }

        @Bean
        ExecutorCapacityGate executorCapacityGate() {
            return new ExecutorCapacityGate();
        }

        @Bean
        TerrakubeClient terrakubeClient() {
            return mock(TerrakubeClient.class);
        }

        @Bean
        OnlineModeServiceImpl onlineModeServiceImpl(
                ExecutorJob executorJob,
                ExecutorCapacityGate executorCapacityGate,
                ApplicationEventPublisher eventPublisher,
                TerrakubeClient terrakubeClient) {
            return new OnlineModeServiceImpl(executorJob, executorCapacityGate, eventPublisher, terrakubeClient);
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class MissingAuthorityMatcherConfig {
        @Bean
        SecurityFilterChain testChain(HttpSecurity http) throws Exception {
            JwtDecoder anyJwtDecoder = token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "authenticated-without-dispatcher-role")
                    .build();
            AuthenticationManagerResolver<HttpServletRequest> resolver =
                    req -> new ProviderManager(new JwtAuthenticationProvider(anyJwtDecoder));
            return http
                    .authorizeHttpRequests(authz -> authz
                            .requestMatchers("/actuator/**").permitAll()
                            .requestMatchers("/api/v1/terraform-rs/**")
                            .hasAuthority(ExecutorManagerResolver.ROLE_API_DISPATCHER)
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(oauth2 -> oauth2.authenticationManagerResolver(resolver))
                    .build();
        }

        @Bean
        OnlineModeServiceImpl onlineModeServiceImpl() {
            return new OnlineModeServiceImpl(
                    mock(ExecutorJob.class),
                    new ExecutorCapacityGate(),
                    event -> { },
                    mock(TerrakubeClient.class));
        }
    }

    private MockMvc mockMvc;
    private ExecutorJob executorJob;
    private ExecutorCapacityGate capacityGate;
    private TerrakubeClient terrakubeClient;
    private WorkspaceSecurityImpl workspaceSecurity;

    @BeforeEach
    void setUp() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestWebConfig.class);
        context.refresh();

        Filter springSecurityFilterChain = context.getBean("springSecurityFilterChain", Filter.class);
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        executorJob = context.getBean(ExecutorJob.class);
        capacityGate = context.getBean(ExecutorCapacityGate.class);
        terrakubeClient = context.getBean(TerrakubeClient.class);
        reset(executorJob, terrakubeClient);
        capacityGate.release();

        when(terrakubeClient.getJobById("org-1", "job-1")).thenReturn(validJobResponse());

        workspaceSecurity = new WorkspaceSecurityImpl(
                new RestClientProperties(),
                "registry.example.com",
                "https://api.example.com",
                SECRET);
    }

    private static ResponseWithInclude<Job, Step> validJobResponse() {
        JobAttributes attrs = new JobAttributes();
        attrs.setStatus("pending");

        Resource ws = new Resource();
        ws.setId("ws-1");
        ws.setType("workspace");
        WorkspaceData wsData = new WorkspaceData();
        wsData.setData(ws);

        Resource org = new Resource();
        org.setId("org-1");
        org.setType("organization");
        OrganizationData orgData = new OrganizationData();
        orgData.setData(org);

        Relationships rel = new Relationships();
        rel.setWorkspace(wsData);
        rel.setOrganization(orgData);

        Job job = new Job();
        job.setId("job-1");
        job.setType("job");
        job.setAttributes(attrs);
        job.setRelationships(rel);

        ResponseWithInclude<Job, Step> resp = new ResponseWithInclude<>();
        resp.setData(job);
        return resp;
    }

    private static String createDispatchToken() {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(SECRET));
        return Jwts.builder()
                .issuer("TERRAKUBE_INTERNAL")
                .subject("Terrakube Internal (Token)")
                .audience().add("TERRAKUBE_INTERNAL").and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(10, ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();
    }

    @Test
    void apiDispatchToken_isAcceptedWith202OnTerraformRsEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/terraform-rs")
                        .header("Authorization", "Bearer " + createDispatchToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JOB_PAYLOAD))
                .andExpect(status().isAccepted());

        verify(executorJob, times(1)).createJob(any(TerraformJob.class));
    }

    @Test
    void runnerToken_isRejectedWith401OnTerraformRsEndpoint() throws Exception {
        String runnerToken = workspaceSecurity.generateAccessToken(60, "org-1", "ws-1", "job-1", "step-1");

        mockMvc.perform(post("/api/v1/terraform-rs")
                        .header("Authorization", "Bearer " + runnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JOB_PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(executorJob, never()).createJob(any());
    }

    @Test
    void executorServiceToken_isRejectedWith401OnTerraformRsEndpoint() throws Exception {
        String executorServiceToken = workspaceSecurity.generateAccessToken(1);

        mockMvc.perform(post("/api/v1/terraform-rs")
                        .header("Authorization", "Bearer " + executorServiceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JOB_PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(executorJob, never()).createJob(any());
    }

    @Test
    void registryServiceToken_isRejectedWith401OnTerraformRsEndpoint() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(SECRET));
        String registryToken = Jwts.builder()
                .issuer("TerrakubeInternal")
                .subject("TerrakubeInternal (REGISTRY)")
                .audience().add("TerrakubeInternal").and()
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(60, ChronoUnit.SECONDS)))
                .signWith(key)
                .compact();

        mockMvc.perform(post("/api/v1/terraform-rs")
                        .header("Authorization", "Bearer " + registryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JOB_PAYLOAD))
                .andExpect(status().isUnauthorized());

        verify(executorJob, never()).createJob(any());
    }

    @Test
    void authenticatedTokenWithoutApiDispatcherAuthority_isRejectedWith403ByMatcher() throws Exception {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(MissingAuthorityMatcherConfig.class);
        context.refresh();

        Filter springSecurityFilterChain = context.getBean("springSecurityFilterChain", Filter.class);
        MockMvc matcherMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();

        matcherMvc.perform(post("/api/v1/terraform-rs")
                        .header("Authorization", "Bearer any-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JOB_PAYLOAD))
                .andExpect(status().isForbidden());
    }
}
