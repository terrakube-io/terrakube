package io.terrakube.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.restassured.RestAssured;
import io.terrakube.api.plugin.scheduler.ScheduleJob;
import io.terrakube.api.plugin.scheduler.job.tcl.TclService;
import io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorService;
import io.terrakube.api.plugin.security.encryption.EncryptionService;
import io.terrakube.api.plugin.token.pat.PatService;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;
import io.terrakube.api.repository.*;
import net.minidev.json.JSONArray;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mock;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
class ServerApplicationTests {

    @MockBean
    protected RedisTemplate<String, Object> redisTemplate;

    @Mock
    protected ValueOperations<String, Object> valueOperations;

    public WireMockServer wireMockServer;

    @LocalServerPort
    int port;

    @Autowired
    BitBucketWebhookService bitBucketWebhookService;

    @Autowired
    EncryptionService encryptionService;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    StepRepository stepRepository;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    TemplateRepository templateRepository;

    @Autowired
    ProjectRepository projectRepository;

    @Autowired
    ScheduleJob scheduleJob;

    @Autowired
    AgentRepository agentRepository;

    @Autowired
    TeamRepository teamRepository;

    @Value("${io.terrakube.token.pat}")
    private String base64Key;

    @Value("${io.terrakube.token.internal}")
    private String base64KeyInternal;

    @Autowired
    PatService patService;

    @Autowired
    TclService tclService;

    @Autowired
    VcsRepository vcsRepository;

    @Autowired
    VariableRepository variableRepository;

    @Autowired
    Scheduler scheduler;

    private static final String ISSUER = "Terrakube";
    private static final String ISSUER_INTERNAL = "TerrakubeInternal";

    @BeforeAll
    public void setUp() {
        RestAssured.port = port;
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();
        // Ensure WireMock static client points to the dynamically started server
        WireMock.configureFor("localhost", wireMockServer.port());
    }


    @AfterAll
    public void stopServer() {
        wireMockServer.stop();
    }

    public String generatePAT(String... activeGroups) {
        JSONArray groups = new JSONArray();
        for (String group : activeGroups)
            groups.appendElement(group);

        String jws = patService.createToken(
                1,
                "Terrakube Test",
                "Terrakube Test",
                "test@terrakube.io",
                groups
        );
        return jws;
    }

    public String generateSystemToken() {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64URL.decode(this.base64KeyInternal));

        String jws = Jwts.builder()
                .setIssuer(ISSUER_INTERNAL)
                .setSubject(String.format("%s (Token)", "Terrakube Test"))
                .setAudience(ISSUER_INTERNAL)
                .setId(UUID.randomUUID().toString())
                .claim("email", "test@terrakube.io")
                .claim("email_verified", true)
                .claim("name", "Terrakube Test")
                .setIssuedAt(Date.from(Instant.now()))
                .setExpiration(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)))
                .signWith(key)
                .compact();

        return jws;
    }

}
