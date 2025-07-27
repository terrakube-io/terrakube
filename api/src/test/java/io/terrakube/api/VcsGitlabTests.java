package io.terrakube.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.mockito.Mockito.when;

public class VcsGitlabTests extends ServerApplicationTests {


    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    @Test
    void gitlabGetIdProject() throws IOException, InterruptedException {
        String simpleSearch="[\n" +
                "    {\n" +
                "        \"id\": 5397249,\n" +
                "        \"path_with_namespace\": \"alfespa17/simple-terraform\"\n" +
                "    }\n" +
                "]";

        stubFor(get(urlPathEqualTo("/projects"))
                .withQueryParam("membership", equalTo("true"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withBody(simpleSearch)));

        GitLabWebhookService gitLabWebhookService = new GitLabWebhookService(new ObjectMapper(), "localhost", "http://localhost", WebClient.builder());

        Assert.isTrue("5397249".equals(gitLabWebhookService.getGitlabProjectId("alfespa17/simple-terraform", "12345", "http://localhost:9999")), "Gitlab project id not found");

        String projectSearch="[\n" +
                "    {\n" +
                "        \"id\": 7138024,\n" +
                "        \"path\": \"simple-terraform\",\n" +
                "        \"path_with_namespace\": \"terraform2745926/simple-terraform\"\n" +
                "    },\n" +
                "    {\n" +
                "        \"id\": 7107040,\n" +
                "        \"path_with_namespace\": \"terraform2745926/test/simple-terraform\"\n" +
                "    }\n" +
                "]";
        stubFor(get(urlPathEqualTo("/projects"))
                .withQueryParam("membership", equalTo("true"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withBody(projectSearch)));

        Assert.isTrue(("7107040".equals(gitLabWebhookService.getGitlabProjectId("terraform2745926/test/simple-terraform", "12345", "http://localhost:9999"))), "Gitlab project id not found");

        stubFor(get(urlPathEqualTo("/projects"))
                .withQueryParam("membership", equalTo("true"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withBody(projectSearch)));

        Assert.isTrue("7138024".equals(gitLabWebhookService.getGitlabProjectId("terraform2745926/simple-terraform", "12345", "http://localhost:9999")), "Gitlab project id not found");

    }

    @Test
    void gitlabWebhookMergeRequest() throws IOException, InterruptedException {
        String mergeRequestPayload="{\n" +
                "   \"object_kind\":\"merge_request\",\n" +
                "   \"event_type\":\"merge_request\",\n" +
                "   \"user\":{\n" +
                "      \"id\":3130582,\n" +
                "      \"name\":\"dummyuser\",\n" +
                "      \"username\":\"dummyuser\",\n" +
                "      \"avatar_url\":\"https://secure.gravatar.com/avatar/148af845d0cf0d25f6fc39049deade6c543e3b2cea2558de5cf8005f8a75274f?s=80&d=identicon\",\n" +
                "      \"email\":\"[REDACTED]\"\n" +
                "   },\n" +
                "   \"project\":{\n" +
                "      \"id\":71070409,\n" +
                "      \"name\":\"Simple Terraform\",\n" +
                "      \"description\":null,\n" +
                "      \"web_url\":\"https://gitlab.com/terraform2745926/test/simple-terraform\",\n" +
                "      \"avatar_url\":null,\n" +
                "      \"git_ssh_url\":\"git@gitlab.com:terraform2745926/test/simple-terraform.git\",\n" +
                "      \"git_http_url\":\"https://gitlab.com/terraform2745926/test/simple-terraform.git\",\n" +
                "      \"namespace\":\"test\",\n" +
                "      \"visibility_level\":0,\n" +
                "      \"path_with_namespace\":\"terraform2745926/test/simple-terraform\",\n" +
                "      \"default_branch\":\"main\",\n" +
                "      \"ci_config_path\":\"\",\n" +
                "      \"homepage\":\"https://gitlab.com/terraform2745926/test/simple-terraform\",\n" +
                "      \"url\":\"git@gitlab.com:terraform2745926/test/simple-terraform.git\",\n" +
                "      \"ssh_url\":\"git@gitlab.com:terraform2745926/test/simple-terraform.git\",\n" +
                "      \"http_url\":\"https://gitlab.com/terraform2745926/test/simple-terraform.git\"\n" +
                "   },\n" +
                "   \"object_attributes\":{\n" +
                "      \"assignee_id\":null,\n" +
                "      \"author_id\":3130582,\n" +
                "      \"created_at\":\"2025-07-15 15:40:39 UTC\",\n" +
                "      \"description\":\"\",\n" +
                "      \"draft\":false,\n" +
                "      \"head_pipeline_id\":null,\n" +
                "      \"id\":399531372,\n" +
                "      \"iid\":2,\n" +
                "      \"last_edited_at\":null,\n" +
                "      \"last_edited_by_id\":null,\n" +
                "      \"merge_commit_sha\":null,\n" +
                "      \"merge_error\":null,\n" +
                "      \"merge_params\":{\n" +
                "         \"force_remove_source_branch\":\"1\"\n" +
                "      },\n" +
                "      \"merge_status\":\"checking\",\n" +
                "      \"merge_user_id\":null,\n" +
                "      \"merge_when_pipeline_succeeds\":false,\n" +
                "      \"milestone_id\":null,\n" +
                "      \"source_branch\":\"feat/pull\",\n" +
                "      \"source_project_id\":71070409,\n" +
                "      \"state_id\":1,\n" +
                "      \"target_branch\":\"main\",\n" +
                "      \"target_project_id\":71070409,\n" +
                "      \"time_estimate\":0,\n" +
                "      \"title\":\"Edit main.tf\",\n" +
                "      \"updated_at\":\"2025-07-15 15:40:41 UTC\",\n" +
                "      \"updated_by_id\":null,\n" +
                "      \"prepared_at\":\"2025-07-15 15:40:41 UTC\",\n" +
                "      \"assignee_ids\":[\n" +
                "         \n" +
                "      ],\n" +
                "      \"blocking_discussions_resolved\":true,\n" +
                "      \"detailed_merge_status\":\"checking\",\n" +
                "      \"first_contribution\":true,\n" +
                "      \"human_time_change\":null,\n" +
                "      \"human_time_estimate\":null,\n" +
                "      \"human_total_time_spent\":null,\n" +
                "      \"labels\":[\n" +
                "         \n" +
                "      ],\n" +
                "      \"last_commit\":{\n" +
                "         \"id\":\"f594713ceba879cb244ea521ceaf3d2e08257533\",\n" +
                "         \"message\":\"Edit main.tf\",\n" +
                "         \"title\":\"Edit main.tf\",\n" +
                "         \"timestamp\":\"2025-07-15T15:39:54+00:00\",\n" +
                "         \"url\":\"https://gitlab.com/terraform2745926/test/simple-terraform/-/commit/f594713ceba879cb244ea521ceaf3d2e08257533\",\n" +
                "         \"author\":{\n" +
                "            \"name\":\"dummyuser\",\n" +
                "            \"email\":\"dummyuser@gmail.com\"\n" +
                "         }\n" +
                "      },\n" +
                "      \"reviewer_ids\":[\n" +
                "         \n" +
                "      ],\n" +
                "      \"source\":{\n" +
                "         \"id\":71070409,\n" +
                "         \"name\":\"Simple Terraform\",\n" +
                "         \"description\":null,\n" +
                "         \"web_url\":\"https://gitlab.com/terraform2745926/test/simple-terraform\",\n" +
                "         \"avatar_url\":null,\n" +
                "         \"git_ssh_url\":\"git@gitlab.com:terraform2745926/test/simple-terraform.git\",\n" +
                "         \"git_http_url\":\"https://gitlab.com/terraform2745926/test/simple-terraform.git\",\n" +
                "         \"namespace\":\"test\",\n" +
                "         \"visibility_level\":0,\n" +
                "         \"path_with_namespace\":\"terraform2745926/test/simple-terraform\",\n" +
                "         \"default_branch\":\"main\",\n" +
                "         \"ci_config_path\":\"\",\n" +
                "         \"homepage\":\"https://gitlab.com/terraform2745926/test/simple-terraform\",\n" +
                "         \"url\":\"git@gitlab.com:terraform2745926/test/simple-terraform.git\",\n" +
                "         \"ssh_url\":\"git@gitlab.com:terraform2745926/test/simple-terraform.git\",\n" +
                "         \"http_url\":\"https://gitlab.com/terraform2745926/test/simple-terraform.git\"\n" +
                "      },\n" +
                "      \"state\":\"opened\",\n" +
                "      \"target\":{\n" +
                "         \"id\":71070409,\n" +
                "         \"name\":\"Simple Terraform\",\n" +
                "         \"description\":null,\n" +
                "         \"web_url\":\"https://gitlab.com/terraform2745926/test/simple-terraform\",\n" +
                "         \"avatar_url\":null,\n" +
                "         \"git_ssh_url\":\"git@gitlab.com:terraform2745926/test/simple-terraform.git\",\n" +
                "         \"git_http_url\":\"https://gitlab.com/terraform2745926/test/simple-terraform.git\",\n" +
                "         \"namespace\":\"test\",\n" +
                "         \"visibility_level\":0,\n" +
                "         \"path_with_namespace\":\"terraform2745926/test/simple-terraform\",\n" +
                "         \"default_branch\":\"main\",\n" +
                "         \"ci_config_path\":\"\",\n" +
                "         \"homepage\":\"https://gitlab.com/terraform2745926/test/simple-terraform\",\n" +
                "         \"url\":\"git@gitlab.com:terraform2745926/test/simple-terraform.git\",\n" +
                "         \"ssh_url\":\"git@gitlab.com:terraform2745926/test/simple-terraform.git\",\n" +
                "         \"http_url\":\"https://gitlab.com/terraform2745926/test/simple-terraform.git\"\n" +
                "      },\n" +
                "      \"time_change\":0,\n" +
                "      \"total_time_spent\":0,\n" +
                "      \"url\":\"https://gitlab.com/terraform2745926/test/simple-terraform/-/merge_requests/2\",\n" +
                "      \"work_in_progress\":false,\n" +
                "      \"approval_rules\":[\n" +
                "         \n" +
                "      ],\n" +
                "      \"action\":\"open\"\n" +
                "   },\n" +
                "   \"labels\":[\n" +
                "      \n" +
                "   ],\n" +
                "   \"changes\":{\n" +
                "      \"merge_status\":{\n" +
                "         \"previous\":\"preparing\",\n" +
                "         \"current\":\"checking\"\n" +
                "      },\n" +
                "      \"updated_at\":{\n" +
                "         \"previous\":\"2025-07-15 15:40:39 UTC\",\n" +
                "         \"current\":\"2025-07-15 15:40:41 UTC\"\n" +
                "      },\n" +
                "      \"prepared_at\":{\n" +
                "         \"previous\":null,\n" +
                "         \"current\":\"2025-07-15 15:40:41 UTC\"\n" +
                "      }\n" +
                "   },\n" +
                "   \"repository\":{\n" +
                "      \"name\":\"Simple Terraform\",\n" +
                "      \"url\":\"git@gitlab.com:terraform2745926/test/simple-terraform.git\",\n" +
                "      \"description\":null,\n" +
                "      \"homepage\":\"https://gitlab.com/terraform2745926/test/simple-terraform\"\n" +
                "   }\n" +
                "}";

        String simpleSearch="[\n" +
                "    {\n" +
                "        \"id\": 71070409,\n" +
                "        \"path_with_namespace\": \"terraform2745926/test/simple-terraform\"\n" +
                "    }\n" +
                "]";

        stubFor(get(urlPathEqualTo("/api/v4/projects"))
                .withQueryParam("membership", equalTo("true"))
                .withQueryParam("per_page", equalTo("25"))
                .withQueryParam("page", equalTo("1"))
                .withHeader("authorization", equalTo("Bearer 1234567890"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withBody(simpleSearch)));

        String mergeRequestDiffPayload="[\n" +
                "    {\n" +
                "        \"diff\": \"@@ -12,6 +12,20 @@ resource \\\"time_sleep\\\" \\\"wait_30_seconds\\\" {\\n   create_duration = local.time\\n }\\n \\n+resource \\\"time_sleep\\\" \\\"wait_30_seconds2\\\" {\\n+  \\n+  depends_on = [null_resource.previous]\\n+\\n+  create_duration = local.time\\n+}\\n+\\n+resource \\\"time_sleep\\\" \\\"wait_30_seconds3\\\" {\\n+  \\n+  depends_on = [null_resource.previous]\\n+\\n+  create_duration = local.time\\n+}\\n+\\n # This resource will create (at least) 30 seconds after null_resource.previous\\n resource \\\"null_resource\\\" \\\"next\\\" {\\n   depends_on = [time_sleep.wait_30_seconds]\\n\",\n" +
                "        \"new_path\": \"main.tf\",\n" +
                "        \"old_path\": \"main.tf\",\n" +
                "        \"a_mode\": \"100644\",\n" +
                "        \"b_mode\": \"100644\",\n" +
                "        \"new_file\": false,\n" +
                "        \"renamed_file\": false,\n" +
                "        \"deleted_file\": false,\n" +
                "        \"generated_file\": false\n" +
                "    }\n" +
                "]";

        stubFor(get(urlPathEqualTo("/api/v4/projects/71070409/merge_requests/2/diffs"))
                .withQueryParam("per_page", equalTo("25"))
                .withQueryParam("page", equalTo("1"))
                .withHeader("authorization", equalTo("Bearer 1234567890"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withHeader("Content-Type", "application/json")
                        .withHeader("x-next-page", "")
                        .withHeader("x-page", "1")
                        .withHeader("x-total", "1")
                        .withBody(mergeRequestDiffPayload)));

        GitLabWebhookService gitLabWebhookService = new GitLabWebhookService(new ObjectMapper(), "localhost", "http://localhost", WebClient.builder());

        Vcs vcs = new Vcs();
        vcs.setAccessToken("1234567890");
        vcs.setClientId("123");
        vcs.setClientSecret("123");
        vcs.setAccessToken("1234567890");
        vcs.setName("gitlab");
        vcs.setDescription("1234");
        vcs.setVcsType(VcsType.GITLAB);
        vcs.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        vcs.setApiUrl("http://localhost:9999/api/v4");
        vcs = vcsRepository.save(vcs);

        Workspace workspace = new Workspace();
        workspace.setName(UUID.randomUUID().toString());
        workspace.setSource("https://gitlab.com/terraform2745926/test/simple-terraform.git");
        workspace.setBranch("main");
        workspace.setIacType("terraform");
        workspace.setTerraformVersion("1.0");
        workspace.setVcs(vcs);
        workspace.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        workspace = workspaceRepository.save(workspace);

        String base64WorkspaceId = Base64.getEncoder()
                .encodeToString(workspace.getId().toString().getBytes(StandardCharsets.UTF_8));

        Map<String, String> headers = new HashMap<>();
        headers.put("x-gitlab-token", base64WorkspaceId);
        WebhookResult webhookResult = new WebhookResult();
        webhookResult.setWorkspaceId(workspace.getId().toString());
        webhookResult = gitLabWebhookService.processWebhook(mergeRequestPayload,headers,base64WorkspaceId,workspace);

        Assert.isTrue(webhookResult.getFileChanges().size()==1,"File changes is not 1");
        Assert.isTrue(webhookResult.getFileChanges().get(0).equals("main.tf"),"File changes is not main.tf");

        workspace.setDeleted(true);
        workspace.setVcs(null);
        workspaceRepository.save(workspace);
        vcsRepository.delete(vcs);

    }

}
