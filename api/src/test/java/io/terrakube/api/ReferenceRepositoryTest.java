package io.terrakube.api;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.terrakube.api.repository.CollectionRepository;
import io.terrakube.api.repository.ItemRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.ReferenceRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.collection.Collection;
import io.terrakube.api.rs.collection.Reference;
import io.terrakube.api.rs.collection.item.Item;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Category;

import static org.assertj.core.api.Assertions.assertThat;

// Deliberately NOT @Transactional at the class level: ExecutorService.loadDefault calls
// referenceRepository.findByWorkspaceWithCollectionItems() after ScheduleJob has already released
// its transaction before making external calls (see ScheduleJob's class comment) - this test
// reproduces that exact condition by giving the repository call no ambient transaction of its own
// either. A plain findByWorkspace() followed by collection.getItem() would throw
// LazyInitializationException here exactly as it did in production (Collection.item is a lazy
// @OneToMany with no fetch override).
class ReferenceRepositoryTest extends ServerApplicationTests {

    @Autowired
    ReferenceRepository referenceRepository;

    @Autowired
    CollectionRepository collectionRepository;

    @Autowired
    ItemRepository itemRepository;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Test
    void findByWorkspaceWithCollectionItemsDoesNotThrowLazyInitializationException() {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();

        Collection collection = new Collection();
        collection.setName("regression-test-collection-" + UUID.randomUUID());
        collection.setDescription("Regression test collection");
        collection.setPriority(1);
        collection.setOrganization(organization);
        collection = collectionRepository.saveAndFlush(collection);

        Item item = new Item();
        item.setKey("TEST_KEY");
        item.setValue("test-value");
        item.setCategory(Category.ENV);
        item.setCollection(collection);
        itemRepository.saveAndFlush(item);

        Reference reference = new Reference();
        reference.setWorkspace(workspace);
        reference.setCollection(collection);
        referenceRepository.saveAndFlush(reference);

        List<Reference> results = referenceRepository.findByWorkspaceWithCollectionItems(workspace);

        Collection finalCollection = collection;
        Reference found = results.stream()
                .filter(r -> r.getCollection().getId().equals(finalCollection.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(found.getCollection().getItem()).extracting(Item::getKey).contains("TEST_KEY");
    }
}
