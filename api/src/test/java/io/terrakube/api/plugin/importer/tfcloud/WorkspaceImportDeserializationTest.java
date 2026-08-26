package io.terrakube.api.plugin.importer.tfcloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceImportDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializeWorkspaceBranchAndWorkingDirectory() throws Exception {
        String payload = """
                {
                  "data": [
                    {
                      "id": "ws-123",
                      "type": "workspaces",
                      "attributes": {
                        "name": "example-workspace",
                        "description": "Imported from Terraform Cloud",
                        "working-directory": "envs/prod",
                        "terraform-version": "1.8.5",
                        "execution-mode": "remote",
                        "vcs-repo": {
                          "identifier": "example-org/example-repo",
                          "service-provider": "github",
                          "repository-http-url": "https://github.com/example-org/example-repo.git",
                          "branch": "release/prod"
                        }
                      }
                    }
                  ],
                  "meta": {
                    "pagination": {
                      "current-page": 1,
                      "next-page": null,
                      "prev-page": null,
                      "total-pages": 1,
                      "total-count": 1
                    }
                  }
                }
                """;

        WorkspaceListResponse response = objectMapper.readValue(payload, WorkspaceListResponse.class);
        WorkspaceImport.WorkspaceData workspace = response.getData().getFirst();

        assertEquals("release/prod", workspace.getAttributes().getVcsRepo().getBranch());
        assertEquals("envs/prod", workspace.getAttributes().getWorkingDirectory());
    }

    @Test
    void shouldDeserializeWorkspaceVarsets() throws Exception {
        String payload = """
                {
                  "data": [
                    {
                      "id": "varset-123",
                      "type": "varsets",
                      "attributes": {
                        "name": "shared-prod"
                      }
                    }
                  ],
                  "meta": {
                    "pagination": {
                      "current-page": 1,
                      "next-page": null,
                      "prev-page": null,
                      "total-pages": 1,
                      "total-count": 1
                    }
                  }
                }
                """;

        VarsetListResponse response = objectMapper.readValue(payload, VarsetListResponse.class);
        VarsetListResponse.VarsetData varset = response.getData().getFirst();

        assertEquals("varset-123", varset.getId());
        assertEquals("shared-prod", varset.getAttributes().getName());
    }
}
