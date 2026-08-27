package io.terrakube.api.plugin.vcs.controller;

import io.terrakube.api.plugin.vcs.discovery.VcsDiscoveryNotSupportedException;
import io.terrakube.api.plugin.vcs.discovery.VcsGroupSummary;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryDiscoveryFacade;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryPage;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.vcs.Vcs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VcsRepositoryControllerTest {

    VcsRepositoryDiscoveryFacade discoveryFacade;
    VcsRepository vcsRepository;
    VcsRepositoryController subject;

    final UUID vcsId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        discoveryFacade = mock(VcsRepositoryDiscoveryFacade.class);
        vcsRepository = mock(VcsRepository.class);
        subject = new VcsRepositoryController(discoveryFacade, vcsRepository);
    }

    private Vcs vcs() {
        Vcs vcs = new Vcs();
        vcs.setId(vcsId);
        return vcs;
    }

    @Test
    void listGroupsReturns404WhenVcsMissing() {
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.empty());

        ResponseEntity<List<VcsGroupSummary>> response = subject.listGroups(vcsId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listGroupsReturnsFacadeResult() {
        Vcs vcs = vcs();
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.of(vcs));
        List<VcsGroupSummary> groups = Collections.singletonList(VcsGroupSummary.builder().id("a").name("a").build());
        when(discoveryFacade.listGroups(vcs)).thenReturn(groups);

        ResponseEntity<List<VcsGroupSummary>> response = subject.listGroups(vcsId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(groups);
    }

    @Test
    void listGroupsReturns422WhenDiscoveryNotSupported() {
        Vcs vcs = vcs();
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.of(vcs));
        when(discoveryFacade.listGroups(vcs)).thenThrow(new VcsDiscoveryNotSupportedException("nope"));

        ResponseEntity<List<VcsGroupSummary>> response = subject.listGroups(vcsId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void listGroupsReturns500OnUnexpectedError() {
        Vcs vcs = vcs();
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.of(vcs));
        when(discoveryFacade.listGroups(vcs)).thenThrow(new RuntimeException("boom"));

        ResponseEntity<List<VcsGroupSummary>> response = subject.listGroups(vcsId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void listRepositoriesReturns404WhenVcsMissing() {
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.empty());

        ResponseEntity<VcsRepositoryPage> response = subject.listRepositories(vcsId.toString(), "group", "", 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listRepositoriesReturnsFacadeResult() {
        Vcs vcs = vcs();
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.of(vcs));
        VcsRepositoryPage page = VcsRepositoryPage.builder().items(Collections.emptyList()).page(1).hasMore(false).build();
        when(discoveryFacade.listRepositories(vcs, "group", "search", 1)).thenReturn(page);

        ResponseEntity<VcsRepositoryPage> response = subject.listRepositories(vcsId.toString(), "group", "search", 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(page);
    }

    @Test
    void listRepositoriesReturns422WhenDiscoveryNotSupported() {
        Vcs vcs = vcs();
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.of(vcs));
        when(discoveryFacade.listRepositories(vcs, "group", "", 1)).thenThrow(new VcsDiscoveryNotSupportedException("nope"));

        ResponseEntity<VcsRepositoryPage> response = subject.listRepositories(vcsId.toString(), "group", "", 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void listRepositoriesReturns500OnUnexpectedError() {
        Vcs vcs = vcs();
        when(vcsRepository.findById(vcsId)).thenReturn(Optional.of(vcs));
        when(discoveryFacade.listRepositories(vcs, "group", "", 1)).thenThrow(new RuntimeException("boom"));

        ResponseEntity<VcsRepositoryPage> response = subject.listRepositories(vcsId.toString(), "group", "", 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
