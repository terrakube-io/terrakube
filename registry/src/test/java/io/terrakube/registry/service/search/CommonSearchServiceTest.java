package io.terrakube.registry.service.search;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.graphql.GraphQLRequest;
import io.terrakube.client.model.graphql.GraphQLResponse;
import io.terrakube.client.model.graphql.queries.search.organization.OrganizationConnection;
import io.terrakube.client.model.graphql.queries.search.organization.OrganizationEdge;
import io.terrakube.client.model.graphql.queries.search.organization.OrganizationNode;
import io.terrakube.client.model.graphql.queries.search.organization.SearchOrganizationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommonSearchServiceTest {

    @Mock
    private TerrakubeClient terrakubeClient;

    private CommonSearchService commonSearchService;

    @BeforeEach
    void setUp() {
        commonSearchService = new CommonSearchService(terrakubeClient);
    }

    @Test
    void getOrganizationId_rejectsRsqlAndGraphQlInjectionPayloads() {
        for (String bad : List.of("simple,name==aws", "simple*", "simple\"", "simple;id!=null", "simple%2Caws", "")) {
            assertNull(commonSearchService.getOrganizationId(bad), "Expected null for: " + bad);
        }
        assertNull(commonSearchService.getOrganizationId(null));
        verifyNoInteractions(terrakubeClient);
    }

    @Test
    void getOrganizationId_requiresUniqueExactNameMatch() {
        when(terrakubeClient.searchOrganization(any(GraphQLRequest.class)))
                .thenReturn(orgResponse(node("id-aws", "aws"), node("id-simple", "simple")))
                .thenReturn(orgResponse(node("id-1", "simple"), node("id-2", "simple")))
                .thenReturn(orgResponse(node("id-aws", "aws")));

        assertEquals("id-simple", commonSearchService.getOrganizationId("simple"));
        assertNull(commonSearchService.getOrganizationId("simple"));
        assertNull(commonSearchService.getOrganizationId("simple"));
    }

    private static OrganizationNode node(String id, String name) {
        OrganizationNode n = new OrganizationNode();
        n.setId(id);
        n.setName(name);
        return n;
    }

    private static GraphQLResponse<SearchOrganizationResponse> orgResponse(OrganizationNode... nodes) {
        List<OrganizationEdge> edges = java.util.Arrays.stream(nodes)
                .map(n -> {
                    OrganizationEdge edge = new OrganizationEdge();
                    edge.setNode(n);
                    return edge;
                })
                .toList();
        OrganizationConnection connection = new OrganizationConnection();
        connection.setEdges(edges);
        SearchOrganizationResponse data = new SearchOrganizationResponse();
        data.setOrganization(connection);
        GraphQLResponse<SearchOrganizationResponse> resp = new GraphQLResponse<>();
        resp.setData(data);
        return resp;
    }
}
