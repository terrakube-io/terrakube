package io.terrakube.api;

import io.terrakube.api.repository.HistoryRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.history.History;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryRepositoryTest extends ServerApplicationTests {

    @Autowired
    HistoryRepository historyRepository;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Test
    void findsJobHistoryAndBaselineBeforeTimestamp() throws Exception {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();

        Workspace workspace = new Workspace();
        workspace.setName("test-history-" + UUID.randomUUID());
        workspace.setSource("https://github.com/AzBuilder/terrakube-docker-compose.git");
        workspace.setBranch("main");
        workspace.setTerraformVersion("1.5.0");
        workspace.setOrganization(organization);
        workspace = workspaceRepository.save(workspace);

        History h1 = new History();
        h1.setWorkspace(workspace);
        h1.setJobReference("101");
        h1.setSerial(1);
        h1.setMd5("md5-initial");
        h1.setLineage("lineage-test");
        h1.setOutput("https://output-1");
        h1 = historyRepository.saveAndFlush(h1);

        Thread.sleep(50);

        History h2 = new History();
        h2.setWorkspace(workspace);
        h2.setJobReference("102");
        h2.setSerial(2);
        h2.setMd5("md5-updated");
        h2.setLineage("lineage-test");
        h2.setOutput("https://output-2");
        h2 = historyRepository.saveAndFlush(h2);

        // 1. Find by workspace and jobReference
        Optional<History> foundJobHistory = historyRepository
                .findFirstByWorkspaceAndJobReferenceOrderByCreatedDateDesc(workspace, "102");
        assertThat(foundJobHistory).isPresent();
        assertThat(foundJobHistory.get().getSerial()).isEqualTo(2);
        assertThat(foundJobHistory.get().getMd5()).isEqualTo("md5-updated");

        // 2. Find baseline before h2.createdDate (should find h1)
        Optional<History> foundBaseline = historyRepository
                .findFirstByWorkspaceAndCreatedDateLessThanOrderByCreatedDateDesc(workspace, h2.getCreatedDate());
        assertThat(foundBaseline).isPresent();
        assertThat(foundBaseline.get().getJobReference()).isEqualTo("101");
        assertThat(foundBaseline.get().getSerial()).isEqualTo(1);

        // 3. Find baseline before h1.createdDate (none before h1)
        Optional<History> noneBeforeH1 = historyRepository
                .findFirstByWorkspaceAndCreatedDateLessThanOrderByCreatedDateDesc(workspace, h1.getCreatedDate());
        assertThat(noneBeforeH1).isEmpty();
    }
}
