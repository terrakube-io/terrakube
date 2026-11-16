package io.terrakube.registry.service.search;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.graphql.GraphQLRequest;
import io.terrakube.client.model.graphql.GraphQLResponse;
import io.terrakube.client.model.graphql.queries.search.organization.OrganizationNode;
import io.terrakube.client.model.graphql.queries.search.organization.SearchOrganizationResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@AllArgsConstructor
@Slf4j
@Service
public class CommonSearchService {

    TerrakubeClient terrakubeClient;

    public static final String SEARCH_ORGANIZATION_GRAPHQL = "{ \n" +
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
        if (!RegistryPathValidator.isValidIdentifier(organizationName)) {
            log.warn("Rejected invalid organization identifier: {}", organizationName);
            return null;
        }

        GraphQLRequest query = new GraphQLRequest();
        query.setQuery(String.format(SEARCH_ORGANIZATION_GRAPHQL, organizationName));
        GraphQLResponse<SearchOrganizationResponse> response = terrakubeClient.searchOrganization(query);
        if (response == null
                || response.getData() == null
                || response.getData().getOrganization() == null
                || response.getData().getOrganization().getEdges() == null) {
            return null;
        }

        List<OrganizationNode> matchingNodes = response.getData().getOrganization().getEdges().stream()
                .map(edge -> edge != null ? edge.getNode() : null)
                .filter(node -> node != null && organizationName.equals(node.getName()))
                .toList();

        if (matchingNodes.size() != 1) {
            log.warn("Expected exactly 1 organization matching '{}', found {}", organizationName, matchingNodes.size());
            return null;
        }

        OrganizationNode organizationNode = matchingNodes.get(0);
        log.info("API response: Organization: {} Id: {}", organizationNode.getName(), organizationNode.getId());
        return organizationNode.getId();
    }
}
