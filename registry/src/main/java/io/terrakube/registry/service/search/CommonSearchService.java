package io.terrakube.registry.service.search;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.graphql.GraphQLRequest;
import io.terrakube.client.model.graphql.GraphQLResponse;
import io.terrakube.client.model.graphql.queries.search.organization.OrganizationNode;
import io.terrakube.client.model.graphql.queries.search.organization.SearchOrganizationResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

@AllArgsConstructor
@Slf4j
@Service
public class CommonSearchService {

    TerrakubeClient terrakubeClient;

    public static final String SEARCH_ORGANIZATION_GRAPHQL="{ \n" +
            "  organization(filter: \"name==%s\") {\n" +
            "    edges {\n" +
            "      node {\n" +
            "        id\n" +
            "        name\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";

    public String getOrganizationId(String organizationName) {
        GraphQLRequest query = new GraphQLRequest();
        query.setQuery(String.format(SEARCH_ORGANIZATION_GRAPHQL, organizationName));
        AtomicReference<String> organizationId = new AtomicReference<>();
        GraphQLResponse<SearchOrganizationResponse> response = terrakubeClient.searchOrganization(query);
        response.getData().getOrganization().getEdges().forEach(organizationEdge -> {
            OrganizationNode organizationNode = organizationEdge.getNode();
            log.info("API response: Organization: {} Id: {}", organizationNode.getName(), organizationNode.getId());
            organizationId.set(organizationNode.getId());
        });

        return organizationId.get();
    }

}
