package io.terrakube.api;

import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.util.Assert;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.mockito.Mockito.when;

public class VcsBitbucketTests extends ServerApplicationTests{

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        wireMockServer.resetAll();
    }

    @Test
    void bitbucketWebhookPullRequest() throws IOException, InterruptedException {
        String payload="{\n" +
                "   \"repository\":{\n" +
                "      \"type\":\"repository\",\n" +
                "      \"full_name\":\"dummyuser/simple-terraform\",\n" +
                "      \"links\":{\n" +
                "         \"self\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform\"\n" +
                "         },\n" +
                "         \"html\":{\n" +
                "            \"href\":\"https://bitbucket.org/dummyuser/simple-terraform\"\n" +
                "         },\n" +
                "         \"avatar\":{\n" +
                "            \"href\":\"https://bytebucket.org/ravatar/%7B5c5211c0-00f6-43d6-ac3e-00963b65241d%7D?ts=default\"\n" +
                "         }\n" +
                "      },\n" +
                "      \"name\":\"simple-terraform\",\n" +
                "      \"scm\":\"git\",\n" +
                "      \"website\":null,\n" +
                "      \"owner\":{\n" +
                "         \"display_name\":\"dummyuser\",\n" +
                "         \"links\":{\n" +
                "            \"self\":{\n" +
                "               \"href\":\"http://localhost:9999/2.0/users/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D\"\n" +
                "            },\n" +
                "            \"avatar\":{\n" +
                "               \"href\":\"https://secure.gravatar.com/avatar/629e77d25d888fc2115bd7626ae004f2?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Finitials%2FAE-4.png\"\n" +
                "            },\n" +
                "            \"html\":{\n" +
                "               \"href\":\"https://bitbucket.org/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D/\"\n" +
                "            }\n" +
                "         },\n" +
                "         \"type\":\"user\",\n" +
                "         \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "         \"account_id\":\"557058:807b67c7-225d-4ed1-b3e0-1e044e0c07af\",\n" +
                "         \"nickname\":\"dummyuser\"\n" +
                "      },\n" +
                "      \"workspace\":{\n" +
                "         \"type\":\"workspace\",\n" +
                "         \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "         \"name\":\"dummyuser\",\n" +
                "         \"slug\":\"dummyuser\",\n" +
                "         \"links\":{\n" +
                "            \"avatar\":{\n" +
                "               \"href\":\"https://bitbucket.org/workspaces/dummyuser/avatar/?ts=1543720870\"\n" +
                "            },\n" +
                "            \"html\":{\n" +
                "               \"href\":\"https://bitbucket.org/dummyuser/\"\n" +
                "            },\n" +
                "            \"self\":{\n" +
                "               \"href\":\"http://localhost:9999/2.0/workspaces/dummyuser\"\n" +
                "            }\n" +
                "         }\n" +
                "      },\n" +
                "      \"is_private\":true,\n" +
                "      \"project\":{\n" +
                "         \"type\":\"project\",\n" +
                "         \"key\":\"TER\",\n" +
                "         \"uuid\":\"{dc8d4f59-d6da-4cf6-9aee-e2b3e75f8a47}\",\n" +
                "         \"name\":\"Terraform\",\n" +
                "         \"links\":{\n" +
                "            \"self\":{\n" +
                "               \"href\":\"http://localhost:9999/2.0/workspaces/dummyuser/projects/TER\"\n" +
                "            },\n" +
                "            \"html\":{\n" +
                "               \"href\":\"https://bitbucket.org/dummyuser/workspace/projects/TER\"\n" +
                "            },\n" +
                "            \"avatar\":{\n" +
                "               \"href\":\"https://bitbucket.org/dummyuser/workspace/projects/TER/avatar/32?ts=1633189494\"\n" +
                "            }\n" +
                "         }\n" +
                "      },\n" +
                "      \"uuid\":\"{5c5211c0-00f6-43d6-ac3e-00963b65241d}\",\n" +
                "      \"parent\":null\n" +
                "   },\n" +
                "   \"actor\":{\n" +
                "      \"display_name\":\"dummyuser\",\n" +
                "      \"links\":{\n" +
                "         \"self\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/users/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D\"\n" +
                "         },\n" +
                "         \"avatar\":{\n" +
                "            \"href\":\"https://secure.gravatar.com/avatar/629e77d25d888fc2115bd7626ae004f2?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Finitials%2FAE-4.png\"\n" +
                "         },\n" +
                "         \"html\":{\n" +
                "            \"href\":\"https://bitbucket.org/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D/\"\n" +
                "         }\n" +
                "      },\n" +
                "      \"type\":\"user\",\n" +
                "      \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "      \"account_id\":\"557058:807b67c7-225d-4ed1-b3e0-1e044e0c07af\",\n" +
                "      \"nickname\":\"dummyuser\"\n" +
                "   },\n" +
                "   \"pullrequest\":{\n" +
                "      \"comment_count\":0,\n" +
                "      \"task_count\":0,\n" +
                "      \"type\":\"pullrequest\",\n" +
                "      \"id\":6,\n" +
                "      \"title\":\"main.tf edited online with Bitbucket\",\n" +
                "      \"description\":\"main.tf edited online with Bitbucket\",\n" +
                "      \"rendered\":{\n" +
                "         \"title\":{\n" +
                "            \"type\":\"rendered\",\n" +
                "            \"raw\":\"main.tf edited online with Bitbucket\",\n" +
                "            \"markup\":\"markdown\",\n" +
                "            \"html\":\"<p>main.tf edited online with Bitbucket</p>\"\n" +
                "         },\n" +
                "         \"description\":{\n" +
                "            \"type\":\"rendered\",\n" +
                "            \"raw\":\"main.tf edited online with Bitbucket\",\n" +
                "            \"markup\":\"markdown\",\n" +
                "            \"html\":\"<p>main.tf edited online with Bitbucket</p>\"\n" +
                "         }\n" +
                "      },\n" +
                "      \"state\":\"OPEN\",\n" +
                "      \"draft\":false,\n" +
                "      \"merge_commit\":null,\n" +
                "      \"close_source_branch\":true,\n" +
                "      \"closed_by\":null,\n" +
                "      \"author\":{\n" +
                "         \"display_name\":\"dummyuser\",\n" +
                "         \"links\":{\n" +
                "            \"self\":{\n" +
                "               \"href\":\"http://localhost:9999/2.0/users/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D\"\n" +
                "            },\n" +
                "            \"avatar\":{\n" +
                "               \"href\":\"https://secure.gravatar.com/avatar/629e77d25d888fc2115bd7626ae004f2?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Finitials%2FAE-4.png\"\n" +
                "            },\n" +
                "            \"html\":{\n" +
                "               \"href\":\"https://bitbucket.org/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D/\"\n" +
                "            }\n" +
                "         },\n" +
                "         \"type\":\"user\",\n" +
                "         \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "         \"account_id\":\"557058:807b67c7-225d-4ed1-b3e0-1e044e0c07af\",\n" +
                "         \"nickname\":\"dummyuser\"\n" +
                "      },\n" +
                "      \"reason\":\"\",\n" +
                "      \"created_on\":\"2025-07-18T16:27:48.770315+00:00\",\n" +
                "      \"updated_on\":\"2025-07-18T16:27:49.582116+00:00\",\n" +
                "      \"destination\":{\n" +
                "         \"branch\":{\n" +
                "            \"name\":\"main\"\n" +
                "         },\n" +
                "         \"commit\":{\n" +
                "            \"hash\":\"f7647c752c7e\",\n" +
                "            \"links\":{\n" +
                "               \"self\":{\n" +
                "                  \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commit/f7647c752c7e\"\n" +
                "               },\n" +
                "               \"html\":{\n" +
                "                  \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/commits/f7647c752c7e\"\n" +
                "               }\n" +
                "            },\n" +
                "            \"type\":\"commit\"\n" +
                "         },\n" +
                "         \"repository\":{\n" +
                "            \"type\":\"repository\",\n" +
                "            \"full_name\":\"dummyuser/simple-terraform\",\n" +
                "            \"links\":{\n" +
                "               \"self\":{\n" +
                "                  \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform\"\n" +
                "               },\n" +
                "               \"html\":{\n" +
                "                  \"href\":\"https://bitbucket.org/dummyuser/simple-terraform\"\n" +
                "               },\n" +
                "               \"avatar\":{\n" +
                "                  \"href\":\"https://bytebucket.org/ravatar/%7B5c5211c0-00f6-43d6-ac3e-00963b65241d%7D?ts=default\"\n" +
                "               }\n" +
                "            },\n" +
                "            \"name\":\"simple-terraform\",\n" +
                "            \"uuid\":\"{5c5211c0-00f6-43d6-ac3e-00963b65241d}\"\n" +
                "         }\n" +
                "      },\n" +
                "      \"source\":{\n" +
                "         \"branch\":{\n" +
                "            \"name\":\"feat/maintf-edited-online-with-bitbucket-1752856064555\",\n" +
                "            \"links\":{\n" +
                "               \n" +
                "            },\n" +
                "            \"sync_strategies\":[\n" +
                "               \"merge_commit\",\n" +
                "               \"rebase\"\n" +
                "            ]\n" +
                "         },\n" +
                "         \"commit\":{\n" +
                "            \"hash\":\"383254320963\",\n" +
                "            \"links\":{\n" +
                "               \"self\":{\n" +
                "                  \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commit/383254320963\"\n" +
                "               },\n" +
                "               \"html\":{\n" +
                "                  \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/commits/383254320963\"\n" +
                "               }\n" +
                "            },\n" +
                "            \"type\":\"commit\"\n" +
                "         },\n" +
                "         \"repository\":{\n" +
                "            \"type\":\"repository\",\n" +
                "            \"full_name\":\"dummyuser/simple-terraform\",\n" +
                "            \"links\":{\n" +
                "               \"self\":{\n" +
                "                  \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform\"\n" +
                "               },\n" +
                "               \"html\":{\n" +
                "                  \"href\":\"https://bitbucket.org/dummyuser/simple-terraform\"\n" +
                "               },\n" +
                "               \"avatar\":{\n" +
                "                  \"href\":\"https://bytebucket.org/ravatar/%7B5c5211c0-00f6-43d6-ac3e-00963b65241d%7D?ts=default\"\n" +
                "               }\n" +
                "            },\n" +
                "            \"name\":\"simple-terraform\",\n" +
                "            \"uuid\":\"{5c5211c0-00f6-43d6-ac3e-00963b65241d}\"\n" +
                "         }\n" +
                "      },\n" +
                "      \"reviewers\":[\n" +
                "         \n" +
                "      ],\n" +
                "      \"participants\":[\n" +
                "         \n" +
                "      ],\n" +
                "      \"links\":{\n" +
                "         \"self\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/pullrequests/6\"\n" +
                "         },\n" +
                "         \"html\":{\n" +
                "            \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/pull-requests/6\"\n" +
                "         },\n" +
                "         \"commits\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/pullrequests/6/commits\"\n" +
                "         },\n" +
                "         \"approve\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/pullrequests/6/approve\"\n" +
                "         },\n" +
                "         \"request-changes\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/pullrequests/6/request-changes\"\n" +
                "         },\n" +
                "         \"diff\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/diff/dummyuser/simple-terraform:383254320963%0Df7647c752c7e?from_pullrequest_id=1&topic=true\"\n" +
                "         },\n" +
                "         \"diffstat\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/diffstat/dummyuser/simple-terraform:383254320963%0Df7647c752c7e?from_pullrequest_id=6&topic=true\"\n" +
                "         },\n" +
                "         \"comments\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/pullrequests/6/comments\"\n" +
                "         },\n" +
                "         \"activity\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/pullrequests/6/activity\"\n" +
                "         },\n" +
                "         \"merge\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/pullrequests/6/merge\"\n" +
                "         },\n" +
                "         \"decline\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/pullrequests/6/decline\"\n" +
                "         },\n" +
                "         \"statuses\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/pullrequests/6/statuses\"\n" +
                "         }\n" +
                "      },\n" +
                "      \"summary\":{\n" +
                "         \"type\":\"rendered\",\n" +
                "         \"raw\":\"main.tf edited online with Bitbucket\",\n" +
                "         \"markup\":\"markdown\",\n" +
                "         \"html\":\"<p>main.tf edited online with Bitbucket</p>\"\n" +
                "      }\n" +
                "   }\n" +
                "}";

        String diffResponse = "diff --git a/main.tf b/main.tf\n" +
                "index f7884af..66c627b 100644\n" +
                "--- a/main.tf\n" +
                "+++ b/main.tf\n" +
                "@@ -23,6 +23,8 @@ output \"creation_time\" {\n" +
                " \n" +
                " \n" +
                " \n" +
                "+\n" +
                "+\n" +
                " output \"fake_data\" {\n" +
                "     value = local.fake\n" +
                " }\n";

        stubFor(get(urlPathEqualTo("/2.0/repositories/dummyuser/simple-terraform/diff/dummyuser/simple-terraform:383254320963%0Df7647c752c7e"))
                .withQueryParam("from_pullrequest_id", equalTo("1"))
                .withQueryParam("topic", equalTo("true"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withBody(diffResponse)));

        Vcs vcs = new Vcs();
        vcs.setAccessToken("1234567890");
        vcs.setClientId("123");
        vcs.setClientSecret("123");
        vcs.setName("bitbucket");
        vcs.setDescription("1234");
        vcs.setVcsType(VcsType.BITBUCKET);
        vcs.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        vcs = vcsRepository.save(vcs);

        Workspace workspace = new Workspace();
        workspace.setName(UUID.randomUUID().toString());
        workspace.setSource("1234");
        workspace.setBranch("main");
        workspace.setIacType("terraform");
        workspace.setTerraformVersion("1.0");
        workspace.setVcs(vcs);
        workspace.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        workspace = workspaceRepository.save(workspace);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-event-key", "pullrequest:created");
        WebhookResult webhookResult = new WebhookResult();
        webhookResult.setWorkspaceId(workspace.getId().toString());
        webhookResult = bitBucketWebhookService.handleEvent(payload,webhookResult, headers);

        Assert.isTrue(webhookResult.getFileChanges().size()==1,"File changes is not 1");

    }

    @Test
    void bitbucketWebhookTag() throws IOException, InterruptedException {
        String payload="{\n" +
                "   \"push\":{\n" +
                "      \"changes\":[\n" +
                "         {\n" +
                "            \"old\":null,\n" +
                "            \"new\":{\n" +
                "               \"name\":\"release/v1.0\",\n" +
                "               \"type\":\"tag\",\n" +
                "               \"message\":null,\n" +
                "               \"date\":null,\n" +
                "               \"tagger\":null,\n" +
                "               \"target\":{\n" +
                "                  \"type\":\"commit\",\n" +
                "                  \"hash\":\"f7647c752c7e7191b49ed4fcf832f8f1ea60cc5c\",\n" +
                "                  \"date\":\"2024-01-19T17:25:41+00:00\",\n" +
                "                  \"author\":{\n" +
                "                     \"type\":\"author\",\n" +
                "                     \"raw\":\"dummyuser <dummyuser@gmail.com>\",\n" +
                "                     \"user\":{\n" +
                "                        \"display_name\":\"dummyuser\",\n" +
                "                        \"links\":{\n" +
                "                           \"self\":{\n" +
                "                              \"href\":\"https://api.bitbucket.org/2.0/users/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D\"\n" +
                "                           },\n" +
                "                           \"avatar\":{\n" +
                "                              \"href\":\"https://secure.gravatar.com/avatar/629e77d25d888fc2115bd7626ae004f2?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Finitials%2FAE-4.png\"\n" +
                "                           },\n" +
                "                           \"html\":{\n" +
                "                              \"href\":\"https://bitbucket.org/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D/\"\n" +
                "                           }\n" +
                "                        },\n" +
                "                        \"type\":\"user\",\n" +
                "                        \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "                        \"account_id\":\"557058:807b67c7-225d-4ed1-b3e0-1e044e0c07af\",\n" +
                "                        \"nickname\":\"dummyuser\"\n" +
                "                     }\n" +
                "                  },\n" +
                "                  \"committer\":{\n" +
                "                     \n" +
                "                  },\n" +
                "                  \"message\":\"main.tf edited online with Bitbucket\",\n" +
                "                  \"summary\":{\n" +
                "                     \"type\":\"rendered\",\n" +
                "                     \"raw\":\"main.tf edited online with Bitbucket\",\n" +
                "                     \"markup\":\"markdown\",\n" +
                "                     \"html\":\"<p>main.tf edited online with Bitbucket</p>\"\n" +
                "                  },\n" +
                "                  \"links\":{\n" +
                "                     \"self\":{\n" +
                "                        \"href\":\"https://api.bitbucket.org/2.0/repositories/dummyuser/simple-terraform/commit/f7647c752c7e7191b49ed4fcf832f8f1ea60cc5c\"\n" +
                "                     },\n" +
                "                     \"html\":{\n" +
                "                        \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/commits/f7647c752c7e7191b49ed4fcf832f8f1ea60cc5c\"\n" +
                "                     }\n" +
                "                  },\n" +
                "                  \"parents\":[\n" +
                "                     {\n" +
                "                        \"hash\":\"ee99ab866b3151b10f817ea1ff127fe8b51cb57c\",\n" +
                "                        \"links\":{\n" +
                "                           \"self\":{\n" +
                "                              \"href\":\"https://api.bitbucket.org/2.0/repositories/dummyuser/simple-terraform/commit/ee99ab866b3151b10f817ea1ff127fe8b51cb57c\"\n" +
                "                           },\n" +
                "                           \"html\":{\n" +
                "                              \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/commits/ee99ab866b3151b10f817ea1ff127fe8b51cb57c\"\n" +
                "                           }\n" +
                "                        },\n" +
                "                        \"type\":\"commit\"\n" +
                "                     }\n" +
                "                  ],\n" +
                "                  \"rendered\":{\n" +
                "                     \n" +
                "                  },\n" +
                "                  \"properties\":{\n" +
                "                     \n" +
                "                  }\n" +
                "               },\n" +
                "               \"links\":{\n" +
                "                  \"self\":{\n" +
                "                     \"href\":\"https://api.bitbucket.org/2.0/repositories/dummyuser/simple-terraform/refs/tags/release/v1.0\"\n" +
                "                  },\n" +
                "                  \"commits\":{\n" +
                "                     \"href\":\"https://api.bitbucket.org/2.0/repositories/dummyuser/simple-terraform/commits/release/v1.0\"\n" +
                "                  },\n" +
                "                  \"html\":{\n" +
                "                     \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/commits/tag/release/v1.0\"\n" +
                "                  }\n" +
                "               }\n" +
                "            },\n" +
                "            \"truncated\":false,\n" +
                "            \"created\":true,\n" +
                "            \"forced\":false,\n" +
                "            \"closed\":false,\n" +
                "            \"links\":{\n" +
                "               \"commits\":{\n" +
                "                  \"href\":\"https://api.bitbucket.org/2.0/repositories/dummyuser/simple-terraform/commits?include=f7647c752c7e7191b49ed4fcf832f8f1ea60cc5c\"\n" +
                "               }\n" +
                "            }\n" +
                "         }\n" +
                "      ]\n" +
                "   },\n" +
                "   \"repository\":{\n" +
                "      \"type\":\"repository\",\n" +
                "      \"full_name\":\"dummyuser/simple-terraform\",\n" +
                "      \"links\":{\n" +
                "         \"self\":{\n" +
                "            \"href\":\"https://api.bitbucket.org/2.0/repositories/dummyuser/simple-terraform\"\n" +
                "         },\n" +
                "         \"html\":{\n" +
                "            \"href\":\"https://bitbucket.org/dummyuser/simple-terraform\"\n" +
                "         },\n" +
                "         \"avatar\":{\n" +
                "            \"href\":\"https://bytebucket.org/ravatar/%7B5c5211c0-00f6-43d6-ac3e-00963b65241d%7D?ts=default\"\n" +
                "         }\n" +
                "      },\n" +
                "      \"name\":\"simple-terraform\",\n" +
                "      \"scm\":\"git\",\n" +
                "      \"website\":null,\n" +
                "      \"owner\":{\n" +
                "         \"display_name\":\"dummyuser\",\n" +
                "         \"links\":{\n" +
                "            \"self\":{\n" +
                "               \"href\":\"https://api.bitbucket.org/2.0/users/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D\"\n" +
                "            },\n" +
                "            \"avatar\":{\n" +
                "               \"href\":\"https://secure.gravatar.com/avatar/629e77d25d888fc2115bd7626ae004f2?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Finitials%2FAE-4.png\"\n" +
                "            },\n" +
                "            \"html\":{\n" +
                "               \"href\":\"https://bitbucket.org/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D/\"\n" +
                "            }\n" +
                "         },\n" +
                "         \"type\":\"user\",\n" +
                "         \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "         \"account_id\":\"557058:807b67c7-225d-4ed1-b3e0-1e044e0c07af\",\n" +
                "         \"nickname\":\"dummyuser\"\n" +
                "      },\n" +
                "      \"workspace\":{\n" +
                "         \"type\":\"workspace\",\n" +
                "         \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "         \"name\":\"dummyuser\",\n" +
                "         \"slug\":\"dummyuser\",\n" +
                "         \"links\":{\n" +
                "            \"avatar\":{\n" +
                "               \"href\":\"https://bitbucket.org/workspaces/dummyuser/avatar/?ts=1543720870\"\n" +
                "            },\n" +
                "            \"html\":{\n" +
                "               \"href\":\"https://bitbucket.org/dummyuser/\"\n" +
                "            },\n" +
                "            \"self\":{\n" +
                "               \"href\":\"https://api.bitbucket.org/2.0/workspaces/dummyuser\"\n" +
                "            }\n" +
                "         }\n" +
                "      },\n" +
                "      \"is_private\":true,\n" +
                "      \"project\":{\n" +
                "         \"type\":\"project\",\n" +
                "         \"key\":\"TER\",\n" +
                "         \"uuid\":\"{dc8d4f59-d6da-4cf6-9aee-e2b3e75f8a47}\",\n" +
                "         \"name\":\"Terraform\",\n" +
                "         \"links\":{\n" +
                "            \"self\":{\n" +
                "               \"href\":\"https://api.bitbucket.org/2.0/workspaces/dummyuser/projects/TER\"\n" +
                "            },\n" +
                "            \"html\":{\n" +
                "               \"href\":\"https://bitbucket.org/dummyuser/workspace/projects/TER\"\n" +
                "            },\n" +
                "            \"avatar\":{\n" +
                "               \"href\":\"https://bitbucket.org/dummyuser/workspace/projects/TER/avatar/32?ts=1633189494\"\n" +
                "            }\n" +
                "         }\n" +
                "      },\n" +
                "      \"uuid\":\"{5c5211c0-00f6-43d6-ac3e-00963b65241d}\",\n" +
                "      \"parent\":null\n" +
                "   },\n" +
                "   \"actor\":{\n" +
                "      \"display_name\":\"dummyuser\",\n" +
                "      \"links\":{\n" +
                "         \"self\":{\n" +
                "            \"href\":\"https://api.bitbucket.org/2.0/users/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D\"\n" +
                "         },\n" +
                "         \"avatar\":{\n" +
                "            \"href\":\"https://secure.gravatar.com/avatar/629e77d25d888fc2115bd7626ae004f2?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Finitials%2FAE-4.png\"\n" +
                "         },\n" +
                "         \"html\":{\n" +
                "            \"href\":\"https://bitbucket.org/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D/\"\n" +
                "         }\n" +
                "      },\n" +
                "      \"type\":\"user\",\n" +
                "      \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "      \"account_id\":\"557058:807b67c7-225d-4ed1-b3e0-1e044e0c07af\",\n" +
                "      \"nickname\":\"dummyuser\"\n" +
                "   }\n" +
                "}";

        Vcs vcs = new Vcs();
        vcs.setAccessToken("1234567890");
        vcs.setClientId("123");
        vcs.setClientSecret("123");
        vcs.setName("bitbucket");
        vcs.setDescription("1234");
        vcs.setVcsType(VcsType.BITBUCKET);
        vcs.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        vcs = vcsRepository.save(vcs);

        Workspace workspace = new Workspace();
        workspace.setName(UUID.randomUUID().toString());
        workspace.setSource("1234");
        workspace.setBranch("main");
        workspace.setIacType("terraform");
        workspace.setTerraformVersion("1.0");
        workspace.setVcs(vcs);
        workspace.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        workspace = workspaceRepository.save(workspace);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-event-key", "repo:push");
        WebhookResult webhookResult = new WebhookResult();
        webhookResult.setWorkspaceId(workspace.getId().toString());
        webhookResult = bitBucketWebhookService.handleEvent(payload,webhookResult, headers);

        Assert.isTrue(webhookResult.isRelease(),"Bitbucket release is not true");

    }

    @Test
    void bitbucketWebhookPush() throws IOException, InterruptedException {
        String payload="{\n" +
                "   \"push\":{\n" +
                "      \"changes\":[\n" +
                "         {\n" +
                "            \"old\":{\n" +
                "               \"name\":\"main\",\n" +
                "               \"target\":{\n" +
                "                  \"type\":\"commit\",\n" +
                "                  \"hash\":\"71085ceb4314ac77d1a36ab687b3e067814c9395\",\n" +
                "                  \"date\":\"2025-07-18T21:09:50+00:00\",\n" +
                "                  \"author\":{\n" +
                "                     \"type\":\"author\",\n" +
                "                     \"raw\":\"dummyuser <dummyuser@gmail.com>\",\n" +
                "                     \"user\":{\n" +
                "                        \"display_name\":\"dummyuser\",\n" +
                "                        \"links\":{\n" +
                "                           \"self\":{\n" +
                "                              \"href\":\"http://localhost:9999/2.0/users/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D\"\n" +
                "                           },\n" +
                "                           \"avatar\":{\n" +
                "                              \"href\":\"https://secure.gravatar.com/avatar/629e77d25d888fc2115bd7626ae004f2?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Finitials%2FAE-4.png\"\n" +
                "                           },\n" +
                "                           \"html\":{\n" +
                "                              \"href\":\"https://bitbucket.org/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D/\"\n" +
                "                           }\n" +
                "                        },\n" +
                "                        \"type\":\"user\",\n" +
                "                        \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "                        \"account_id\":\"557058:807b67c7-225d-4ed1-b3e0-1e044e0c07af\",\n" +
                "                        \"nickname\":\"dummyuser\"\n" +
                "                     }\n" +
                "                  },\n" +
                "                  \"committer\":{\n" +
                "                     \n" +
                "                  },\n" +
                "                  \"message\":\"main.tf edited online with Bitbucket\",\n" +
                "                  \"summary\":{\n" +
                "                     \"type\":\"rendered\",\n" +
                "                     \"raw\":\"main.tf edited online with Bitbucket\",\n" +
                "                     \"markup\":\"markdown\",\n" +
                "                     \"html\":\"<p>main.tf edited online with Bitbucket</p>\"\n" +
                "                  },\n" +
                "                  \"links\":{\n" +
                "                     \"self\":{\n" +
                "                        \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commit/71085ceb4314ac77d1a36ab687b3e067814c9395\"\n" +
                "                     },\n" +
                "                     \"html\":{\n" +
                "                        \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/commits/71085ceb4314ac77d1a36ab687b3e067814c9395\"\n" +
                "                     }\n" +
                "                  },\n" +
                "                  \"parents\":[\n" +
                "                     {\n" +
                "                        \"hash\":\"fc3154e151fea0ca3ae2c4e6f8fbffe54a5a8568\",\n" +
                "                        \"links\":{\n" +
                "                           \"self\":{\n" +
                "                              \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commit/fc3154e151fea0ca3ae2c4e6f8fbffe54a5a8568\"\n" +
                "                           },\n" +
                "                           \"html\":{\n" +
                "                              \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/commits/fc3154e151fea0ca3ae2c4e6f8fbffe54a5a8568\"\n" +
                "                           }\n" +
                "                        },\n" +
                "                        \"type\":\"commit\"\n" +
                "                     }\n" +
                "                  ],\n" +
                "                  \"rendered\":{\n" +
                "                     \n" +
                "                  },\n" +
                "                  \"properties\":{\n" +
                "                     \n" +
                "                  }\n" +
                "               },\n" +
                "               \"links\":{\n" +
                "                  \"self\":{\n" +
                "                     \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/refs/branches/main\"\n" +
                "                  },\n" +
                "                  \"commits\":{\n" +
                "                     \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commits/main\"\n" +
                "                  },\n" +
                "                  \"html\":{\n" +
                "                     \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/branch/main\"\n" +
                "                  },\n" +
                "                  \"pullrequest_create\":{\n" +
                "                     \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/pull-requests/new?source=main&t=1\"\n" +
                "                  }\n" +
                "               },\n" +
                "               \"type\":\"branch\",\n" +
                "               \"merge_strategies\":[\n" +
                "                  \"merge_commit\",\n" +
                "                  \"squash\",\n" +
                "                  \"fast_forward\",\n" +
                "                  \"squash_fast_forward\",\n" +
                "                  \"rebase_fast_forward\",\n" +
                "                  \"rebase_merge\"\n" +
                "               ],\n" +
                "               \"sync_strategies\":[\n" +
                "                  \"merge_commit\",\n" +
                "                  \"rebase\"\n" +
                "               ],\n" +
                "               \"default_merge_strategy\":\"merge_commit\"\n" +
                "            },\n" +
                "            \"new\":{\n" +
                "               \"name\":\"main\",\n" +
                "               \"target\":{\n" +
                "                  \"type\":\"commit\",\n" +
                "                  \"hash\":\"c7eae9b0c5450123c943a4baf7c357fc2564c316\",\n" +
                "                  \"date\":\"2025-07-18T21:20:52+00:00\",\n" +
                "                  \"author\":{\n" +
                "                     \"type\":\"author\",\n" +
                "                     \"raw\":\"dummyuser <dummyuser@gmail.com>\",\n" +
                "                     \"user\":{\n" +
                "                        \"display_name\":\"dummyuser\",\n" +
                "                        \"links\":{\n" +
                "                           \"self\":{\n" +
                "                              \"href\":\"http://localhost:9999/2.0/users/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D\"\n" +
                "                           },\n" +
                "                           \"avatar\":{\n" +
                "                              \"href\":\"https://secure.gravatar.com/avatar/629e77d25d888fc2115bd7626ae004f2?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Finitials%2FAE-4.png\"\n" +
                "                           },\n" +
                "                           \"html\":{\n" +
                "                              \"href\":\"https://bitbucket.org/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D/\"\n" +
                "                           }\n" +
                "                        },\n" +
                "                        \"type\":\"user\",\n" +
                "                        \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "                        \"account_id\":\"557058:807b67c7-225d-4ed1-b3e0-1e044e0c07af\",\n" +
                "                        \"nickname\":\"dummyuser\"\n" +
                "                     }\n" +
                "                  },\n" +
                "                  \"committer\":{\n" +
                "                     \n" +
                "                  },\n" +
                "                  \"message\":\"main.tf edited online with Bitbucket\",\n" +
                "                  \"summary\":{\n" +
                "                     \"type\":\"rendered\",\n" +
                "                     \"raw\":\"main.tf edited online with Bitbucket\",\n" +
                "                     \"markup\":\"markdown\",\n" +
                "                     \"html\":\"<p>main.tf edited online with Bitbucket</p>\"\n" +
                "                  },\n" +
                "                  \"links\":{\n" +
                "                     \"self\":{\n" +
                "                        \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commit/c7eae9b0c5450123c943a4baf7c357fc2564c316\"\n" +
                "                     },\n" +
                "                     \"html\":{\n" +
                "                        \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/commits/c7eae9b0c5450123c943a4baf7c357fc2564c316\"\n" +
                "                     }\n" +
                "                  },\n" +
                "                  \"parents\":[\n" +
                "                     {\n" +
                "                        \"hash\":\"71085ceb4314ac77d1a36ab687b3e067814c9395\",\n" +
                "                        \"links\":{\n" +
                "                           \"self\":{\n" +
                "                              \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commit/71085ceb4314ac77d1a36ab687b3e067814c9395\"\n" +
                "                           },\n" +
                "                           \"html\":{\n" +
                "                              \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/commits/71085ceb4314ac77d1a36ab687b3e067814c9395\"\n" +
                "                           }\n" +
                "                        },\n" +
                "                        \"type\":\"commit\"\n" +
                "                     }\n" +
                "                  ],\n" +
                "                  \"rendered\":{\n" +
                "                     \n" +
                "                  },\n" +
                "                  \"properties\":{\n" +
                "                     \n" +
                "                  }\n" +
                "               },\n" +
                "               \"links\":{\n" +
                "                  \"self\":{\n" +
                "                     \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/refs/branches/main\"\n" +
                "                  },\n" +
                "                  \"commits\":{\n" +
                "                     \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commits/main\"\n" +
                "                  },\n" +
                "                  \"html\":{\n" +
                "                     \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/branch/main\"\n" +
                "                  },\n" +
                "                  \"pullrequest_create\":{\n" +
                "                     \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/pull-requests/new?source=main&t=1\"\n" +
                "                  }\n" +
                "               },\n" +
                "               \"type\":\"branch\",\n" +
                "               \"merge_strategies\":[\n" +
                "                  \"merge_commit\",\n" +
                "                  \"squash\",\n" +
                "                  \"fast_forward\",\n" +
                "                  \"squash_fast_forward\",\n" +
                "                  \"rebase_fast_forward\",\n" +
                "                  \"rebase_merge\"\n" +
                "               ],\n" +
                "               \"sync_strategies\":[\n" +
                "                  \"merge_commit\",\n" +
                "                  \"rebase\"\n" +
                "               ],\n" +
                "               \"default_merge_strategy\":\"merge_commit\"\n" +
                "            },\n" +
                "            \"truncated\":false,\n" +
                "            \"created\":false,\n" +
                "            \"forced\":false,\n" +
                "            \"closed\":false,\n" +
                "            \"links\":{\n" +
                "               \"commits\":{\n" +
                "                  \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commits?include=c7eae9b0c5450123c943a4baf7c357fc2564c316&exclude=71085ceb4314ac77d1a36ab687b3e067814c9395\"\n" +
                "               },\n" +
                "               \"diff\":{\n" +
                "                  \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/diff/c7eae9b0c5450123c943a4baf7c357fc2564c316..71085ceb4314ac77d1a36ab687b3e067814c9395\"\n" +
                "               },\n" +
                "               \"html\":{\n" +
                "                  \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/branches/compare/c7eae9b0c5450123c943a4baf7c357fc2564c316..71085ceb4314ac77d1a36ab687b3e067814c9395\"\n" +
                "               }\n" +
                "            },\n" +
                "            \"commits\":[\n" +
                "               {\n" +
                "                  \"type\":\"commit\",\n" +
                "                  \"hash\":\"c7eae9b0c5450123c943a4baf7c357fc2564c316\",\n" +
                "                  \"date\":\"2025-07-18T21:20:52+00:00\",\n" +
                "                  \"author\":{\n" +
                "                     \"type\":\"author\",\n" +
                "                     \"raw\":\"dummyuser <dummyuser@gmail.com>\",\n" +
                "                     \"user\":{\n" +
                "                        \"display_name\":\"dummyuser\",\n" +
                "                        \"links\":{\n" +
                "                           \"self\":{\n" +
                "                              \"href\":\"http://localhost:9999/2.0/users/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D\"\n" +
                "                           },\n" +
                "                           \"avatar\":{\n" +
                "                              \"href\":\"https://secure.gravatar.com/avatar/629e77d25d888fc2115bd7626ae004f2?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Finitials%2FAE-4.png\"\n" +
                "                           },\n" +
                "                           \"html\":{\n" +
                "                              \"href\":\"https://bitbucket.org/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D/\"\n" +
                "                           }\n" +
                "                        },\n" +
                "                        \"type\":\"user\",\n" +
                "                        \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "                        \"account_id\":\"557058:807b67c7-225d-4ed1-b3e0-1e044e0c07af\",\n" +
                "                        \"nickname\":\"dummyuser\"\n" +
                "                     }\n" +
                "                  },\n" +
                "                  \"committer\":{\n" +
                "                     \n" +
                "                  },\n" +
                "                  \"message\":\"main.tf edited online with Bitbucket\",\n" +
                "                  \"summary\":{\n" +
                "                     \"type\":\"rendered\",\n" +
                "                     \"raw\":\"main.tf edited online with Bitbucket\",\n" +
                "                     \"markup\":\"markdown\",\n" +
                "                     \"html\":\"<p>main.tf edited online with Bitbucket</p>\"\n" +
                "                  },\n" +
                "                  \"links\":{\n" +
                "                     \"self\":{\n" +
                "                        \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commit/c7eae9b0c5450123c943a4baf7c357fc2564c316\"\n" +
                "                     },\n" +
                "                     \"html\":{\n" +
                "                        \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/commits/c7eae9b0c5450123c943a4baf7c357fc2564c316\"\n" +
                "                     },\n" +
                "                     \"diff\":{\n" +
                "                        \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/diff/c7eae9b0c5450123c943a4baf7c357fc2564c316\"\n" +
                "                     },\n" +
                "                     \"approve\":{\n" +
                "                        \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commit/c7eae9b0c5450123c943a4baf7c357fc2564c316/approve\"\n" +
                "                     },\n" +
                "                     \"comments\":{\n" +
                "                        \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commit/c7eae9b0c5450123c943a4baf7c357fc2564c316/comments\"\n" +
                "                     },\n" +
                "                     \"statuses\":{\n" +
                "                        \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commit/c7eae9b0c5450123c943a4baf7c357fc2564c316/statuses\"\n" +
                "                     },\n" +
                "                     \"patch\":{\n" +
                "                        \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/patch/c7eae9b0c5450123c943a4baf7c357fc2564c316\"\n" +
                "                     }\n" +
                "                  },\n" +
                "                  \"parents\":[\n" +
                "                     {\n" +
                "                        \"hash\":\"71085ceb4314ac77d1a36ab687b3e067814c9395\",\n" +
                "                        \"links\":{\n" +
                "                           \"self\":{\n" +
                "                              \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform/commit/71085ceb4314ac77d1a36ab687b3e067814c9395\"\n" +
                "                           },\n" +
                "                           \"html\":{\n" +
                "                              \"href\":\"https://bitbucket.org/dummyuser/simple-terraform/commits/71085ceb4314ac77d1a36ab687b3e067814c9395\"\n" +
                "                           }\n" +
                "                        },\n" +
                "                        \"type\":\"commit\"\n" +
                "                     }\n" +
                "                  ],\n" +
                "                  \"rendered\":{\n" +
                "                     \n" +
                "                  },\n" +
                "                  \"properties\":{\n" +
                "                     \n" +
                "                  }\n" +
                "               }\n" +
                "            ]\n" +
                "         }\n" +
                "      ]\n" +
                "   },\n" +
                "   \"repository\":{\n" +
                "      \"type\":\"repository\",\n" +
                "      \"full_name\":\"dummyuser/simple-terraform\",\n" +
                "      \"links\":{\n" +
                "         \"self\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/repositories/dummyuser/simple-terraform\"\n" +
                "         },\n" +
                "         \"html\":{\n" +
                "            \"href\":\"https://bitbucket.org/dummyuser/simple-terraform\"\n" +
                "         },\n" +
                "         \"avatar\":{\n" +
                "            \"href\":\"https://bytebucket.org/ravatar/%7B5c5211c0-00f6-43d6-ac3e-00963b65241d%7D?ts=default\"\n" +
                "         }\n" +
                "      },\n" +
                "      \"name\":\"simple-terraform\",\n" +
                "      \"scm\":\"git\",\n" +
                "      \"website\":null,\n" +
                "      \"owner\":{\n" +
                "         \"display_name\":\"dummyuser\",\n" +
                "         \"links\":{\n" +
                "            \"self\":{\n" +
                "               \"href\":\"http://localhost:9999/2.0/users/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D\"\n" +
                "            },\n" +
                "            \"avatar\":{\n" +
                "               \"href\":\"https://secure.gravatar.com/avatar/629e77d25d888fc2115bd7626ae004f2?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Finitials%2FAE-4.png\"\n" +
                "            },\n" +
                "            \"html\":{\n" +
                "               \"href\":\"https://bitbucket.org/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D/\"\n" +
                "            }\n" +
                "         },\n" +
                "         \"type\":\"user\",\n" +
                "         \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "         \"account_id\":\"557058:807b67c7-225d-4ed1-b3e0-1e044e0c07af\",\n" +
                "         \"nickname\":\"dummyuser\"\n" +
                "      },\n" +
                "      \"workspace\":{\n" +
                "         \"type\":\"workspace\",\n" +
                "         \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "         \"name\":\"dummyuser\",\n" +
                "         \"slug\":\"dummyuser\",\n" +
                "         \"links\":{\n" +
                "            \"avatar\":{\n" +
                "               \"href\":\"https://bitbucket.org/workspaces/dummyuser/avatar/?ts=1543720870\"\n" +
                "            },\n" +
                "            \"html\":{\n" +
                "               \"href\":\"https://bitbucket.org/dummyuser/\"\n" +
                "            },\n" +
                "            \"self\":{\n" +
                "               \"href\":\"http://localhost:9999/2.0/workspaces/dummyuser\"\n" +
                "            }\n" +
                "         }\n" +
                "      },\n" +
                "      \"is_private\":true,\n" +
                "      \"project\":{\n" +
                "         \"type\":\"project\",\n" +
                "         \"key\":\"TER\",\n" +
                "         \"uuid\":\"{dc8d4f59-d6da-4cf6-9aee-e2b3e75f8a47}\",\n" +
                "         \"name\":\"Terraform\",\n" +
                "         \"links\":{\n" +
                "            \"self\":{\n" +
                "               \"href\":\"http://localhost:9999/2.0/workspaces/dummyuser/projects/TER\"\n" +
                "            },\n" +
                "            \"html\":{\n" +
                "               \"href\":\"https://bitbucket.org/dummyuser/workspace/projects/TER\"\n" +
                "            },\n" +
                "            \"avatar\":{\n" +
                "               \"href\":\"https://bitbucket.org/dummyuser/workspace/projects/TER/avatar/32?ts=1633189494\"\n" +
                "            }\n" +
                "         }\n" +
                "      },\n" +
                "      \"uuid\":\"{5c5211c0-00f6-43d6-ac3e-00963b65241d}\",\n" +
                "      \"parent\":null\n" +
                "   },\n" +
                "   \"actor\":{\n" +
                "      \"display_name\":\"dummyuser\",\n" +
                "      \"links\":{\n" +
                "         \"self\":{\n" +
                "            \"href\":\"http://localhost:9999/2.0/users/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D\"\n" +
                "         },\n" +
                "         \"avatar\":{\n" +
                "            \"href\":\"https://secure.gravatar.com/avatar/629e77d25d888fc2115bd7626ae004f2?d=https%3A%2F%2Favatar-management--avatars.us-west-2.prod.public.atl-paas.net%2Finitials%2FAE-4.png\"\n" +
                "         },\n" +
                "         \"html\":{\n" +
                "            \"href\":\"https://bitbucket.org/%7Bed8beedc-3d28-4a7d-9ff3-de5dccead5f1%7D/\"\n" +
                "         }\n" +
                "      },\n" +
                "      \"type\":\"user\",\n" +
                "      \"uuid\":\"{ed8beedc-3d28-4a7d-9ff3-de5dccead5f1}\",\n" +
                "      \"account_id\":\"557058:807b67c7-225d-4ed1-b3e0-1e044e0c07af\",\n" +
                "      \"nickname\":\"dummyuser\"\n" +
                "   }\n" +
                "}";

        String diffResponse = "diff --git a/main.tf b/main.tf\n" +
                "index f7884af..66c627b 100644\n" +
                "--- a/main.tf\n" +
                "+++ b/main.tf\n" +
                "@@ -23,6 +23,8 @@ output \"creation_time\" {\n" +
                " \n" +
                " \n" +
                " \n" +
                "+\n" +
                "+\n" +
                " output \"fake_data\" {\n" +
                "     value = local.fake\n" +
                " }\n";

        stubFor(get(urlPathEqualTo("/2.0/repositories/dummyuser/simple-terraform/diff/c7eae9b0c5450123c943a4baf7c357fc2564c316..71085ceb4314ac77d1a36ab687b3e067814c9395"))
                .willReturn(aResponse()
                        .withStatus(HttpStatus.OK.value())
                        .withBody(diffResponse)));

        Vcs vcs = new Vcs();
        vcs.setAccessToken("1234567890");
        vcs.setClientId("123");
        vcs.setClientSecret("123");
        vcs.setName("bitbucket");
        vcs.setDescription("1234");
        vcs.setVcsType(VcsType.BITBUCKET);
        vcs.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        vcs = vcsRepository.save(vcs);

        Workspace workspace = new Workspace();
        workspace.setName(UUID.randomUUID().toString());
        workspace.setSource("1234");
        workspace.setBranch("main");
        workspace.setIacType("terraform");
        workspace.setTerraformVersion("1.0");
        workspace.setVcs(vcs);
        workspace.setOrganization(organizationRepository.findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).get());
        workspace = workspaceRepository.save(workspace);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-event-key", "repo:push");
        WebhookResult webhookResult = new WebhookResult();
        webhookResult.setWorkspaceId(workspace.getId().toString());
        webhookResult = bitBucketWebhookService.handleEvent(payload,webhookResult, headers);

        Assert.isTrue(webhookResult.getFileChanges().size()==1,"File changes is not 1");

    }
}
