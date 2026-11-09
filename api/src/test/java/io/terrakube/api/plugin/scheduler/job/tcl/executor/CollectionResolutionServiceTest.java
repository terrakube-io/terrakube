package io.terrakube.api.plugin.scheduler.job.tcl.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.collection.ReferenceScope;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.workspace.Workspace;

class CollectionResolutionServiceTest {

    private ReferenceRepository references;
    private CollectionResolutionService service;
    private final Organization organization = new Organization();
    private final Workspace workspace = new Workspace();
    private final Project project = new Project();

    @BeforeEach
    void setUp() {
        references = mock(ReferenceRepository.class);
        service = new CollectionResolutionService(references);
        workspace.setProject(project);
        when(references.findByWorkspaceWithCollectionItems(workspace)).thenReturn(List.of());
        when(references.findByProjectWithCollectionItems(project, ReferenceScope.PROJECT)).thenReturn(List.of());
        when(references.findByOrganizationWithCollectionItems(organization, ReferenceScope.ORGANIZATION))
                .thenReturn(List.of());
    }

    @Test
    void workspaceBeatsProjectBeatsOrganizationEvenWhenTheWiderCollectionHasHigherPriority() {
        when(references.findByWorkspaceWithCollectionItems(workspace)).thenReturn(List.of(ref("workspace", 1)));
        when(references.findByProjectWithCollectionItems(project, ReferenceScope.PROJECT))
                .thenReturn(List.of(ref("project", 50)));
        when(references.findByOrganizationWithCollectionItems(organization, ReferenceScope.ORGANIZATION))
                .thenReturn(List.of(ref("organization", 99)));

        assertThat(service.resolve(workspace, organization)).extracting(Collection::getName)
                .containsExactly("workspace", "project", "organization");
    }

    @Test
    void higherPriorityWinsWithinAScope() {
        when(references.findByProjectWithCollectionItems(project, ReferenceScope.PROJECT))
                .thenReturn(List.of(ref("low", 1), ref("high", 10), ref("mid", 5)));

        assertThat(service.resolve(workspace, organization)).extracting(Collection::getName)
                .containsExactly("high", "mid", "low");
    }

    @Test
    void aWorkspaceWithoutAProjectSkipsProjectCollections() {
        workspace.setProject(null);

        service.resolve(workspace, organization);

        verify(references, never()).findByProjectWithCollectionItems(any(), any());
    }

    private static Reference ref(String collectionName, int priority) {
        Collection collection = new Collection();
        collection.setName(collectionName);
        collection.setPriority(priority);
        Reference reference = new Reference();
        reference.setCollection(collection);
        return reference;
    }
}
