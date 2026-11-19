package io.terrakube.registry.service.search;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.client.model.graphql.GraphQLResponse;
import io.terrakube.client.model.graphql.queries.search.organization.OrganizationConnection;
import io.terrakube.client.model.graphql.queries.search.organization.OrganizationEdge;
import io.terrakube.client.model.graphql.queries.search.organization.OrganizationNode;
import io.terrakube.client.model.graphql.queries.search.organization.SearchOrganizationResponse;
import io.terrakube.registry.configuration.CacheConfig;
import io.terrakube.registry.configuration.OpenRegistryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommonSearchServiceCacheTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Configuration
    @EnableCaching
    static class TestConfig {
        @Bean
        CacheManager cacheManager() {
            return new CacheConfig().cacheManager(new OpenRegistryProperties());
        }

        @Bean
        TerrakubeClient terrakubeClient() {
            return mock(TerrakubeClient.class);
        }

        @Bean
        CommonSearchService commonSearchService(TerrakubeClient terrakubeClient) {
            return new CommonSearchService(terrakubeClient);
        }
    }

    private static GraphQLResponse<SearchOrganizationResponse> organizations(String id) {
        OrganizationConnection connection = new OrganizationConnection();
        if (id == null) {
            connection.setEdges(List.of());
        } else {
            OrganizationNode node = new OrganizationNode();
            node.setId(id);
            node.setName("org");
            OrganizationEdge edge = new OrganizationEdge();
            edge.setNode(node);
            connection.setEdges(List.of(edge));
        }
        SearchOrganizationResponse search = new SearchOrganizationResponse();
        search.setOrganization(connection);
        GraphQLResponse<SearchOrganizationResponse> response = new GraphQLResponse<>();
        response.setData(search);
        return response;
    }

    @Test
    void organizationIdIsCached() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        TerrakubeClient client = context.getBean(TerrakubeClient.class);
        CommonSearchService service = context.getBean(CommonSearchService.class);
        when(client.searchOrganization(any())).thenReturn(organizations("org-id"));

        assertEquals("org-id", service.getOrganizationId("org"));
        assertEquals("org-id", service.getOrganizationId("org"));

        verify(client, times(1)).searchOrganization(any());
    }

    @Test
    void missingOrganizationIsNotCached() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        TerrakubeClient client = context.getBean(TerrakubeClient.class);
        CommonSearchService service = context.getBean(CommonSearchService.class);
        when(client.searchOrganization(any())).thenReturn(organizations(null), organizations("org-id"));

        assertNull(service.getOrganizationId("org"));
        assertEquals("org-id", service.getOrganizationId("org"));

        verify(client, times(2)).searchOrganization(any());
    }
}
