package io.terrakube.api.plugin.state;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceListQueryTest {

    @Test
    void parsesEveryFilterTheTerraformCliSends() {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("filter[tagged][1][key]", "team");
        parameters.add("filter[tagged][1][value]", "infra");
        parameters.add("filter[tagged][0][key]", "env");
        parameters.add("filter[tagged][0][value]", "");
        parameters.add("search[tags]", "env, team,");
        parameters.add("search[name]", "app");
        parameters.add("filter[project][id]", "prj-1");
        parameters.add("page[number]", "2");

        WorkspaceListQuery query = WorkspaceListQuery.from(parameters);

        assertEquals(Set.of(new WorkspaceListQuery.TagFilter("env", ""), new WorkspaceListQuery.TagFilter("team", "infra")),
                Set.copyOf(query.tagged()));
        assertFalse(new WorkspaceListQuery.TagFilter("env", "").hasValue());
        assertTrue(new WorkspaceListQuery.TagFilter("team", "infra").hasValue());
        assertEquals(List.of("env", "team"), query.searchTags());
        assertEquals("app", query.searchName());
        assertEquals("prj-1", query.projectId());
        assertEquals(2, query.pageNumber());
        assertTrue(query.hasAnyCondition());
    }

    @Test
    void pairsKeysAndValuesBySparseIndexAndIgnoresPairsWithoutKey() {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("filter[tagged][7][value]", "prod");
        parameters.add("filter[tagged][7][key]", "env");
        parameters.add("filter[tagged][9][value]", "orphan");
        parameters.add("filter[tagged][99999999999999999999][key]", "big");
        parameters.add("filter[tag-bindings][0][key]", "not-a-cli-parameter");

        WorkspaceListQuery query = WorkspaceListQuery.from(parameters);

        // The index only pairs a key with its value; the order carries no meaning
        assertEquals(Set.of(new WorkspaceListQuery.TagFilter("big", ""), new WorkspaceListQuery.TagFilter("env", "prod")),
                Set.copyOf(query.tagged()));
    }

    @Test
    void emptyParametersMeanNoCondition() {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("search[name]", "");
        parameters.add("search[tags]", "");
        parameters.add("page[number]", "not-a-number");

        WorkspaceListQuery query = WorkspaceListQuery.from(parameters);

        assertFalse(query.hasAnyCondition());
        assertNull(query.searchName());
        assertEquals(1, query.pageNumber());
    }

    @Test
    void pageSizeDefaultsToTwentyAndIsCappedAtOneHundred() {
        assertEquals(20, pageSize(null));
        assertEquals(5, pageSize("5"));
        assertEquals(100, pageSize("500"));
        assertEquals(20, pageSize("0"));
        assertEquals(20, pageSize("not-a-number"));
    }

    @Test
    void offsetSkipsThePreviousPages() {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        parameters.add("page[number]", "3");
        parameters.add("page[size]", "10");

        assertEquals(20, WorkspaceListQuery.from(parameters).offset());
    }

    private static int pageSize(String value) {
        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        if (value != null) {
            parameters.add("page[size]", value);
        }
        return WorkspaceListQuery.from(parameters).pageSize();
    }
}
