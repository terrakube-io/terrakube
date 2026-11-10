package io.terrakube.api.plugin.vcs.provider.gitlab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GitlabDiffResponseModelTest {

    @Test
    void ignoresFieldsGitLabAddsToTheDiffResponse() throws Exception {
        ObjectMapper strict = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        String json = "[{\"old_path\":\"a.tf\",\"new_path\":\"a.tf\",\"a_mode\":\"100644\",\"b_mode\":\"100644\","
                + "\"diff\":\"@@\",\"new_file\":false,\"renamed_file\":false,\"deleted_file\":false,"
                + "\"generated_file\":false,\"collapsed\":false,\"too_large\":false}]";

        GitlabDiffResponseModel[] models = strict.readValue(json, GitlabDiffResponseModel[].class);

        assertEquals(1, models.length);
        assertEquals("a.tf", models[0].getNewPath());
    }
}
