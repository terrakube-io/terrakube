package io.terrakube.api;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.terrakube.api.repository.AddressRepository;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.address.Address;
import io.terrakube.api.rs.job.address.AddressType;
import io.terrakube.api.rs.workspace.Workspace;

import static org.assertj.core.api.Assertions.assertThat;

// Deliberately NOT @Transactional at the class level: ExecutorService.validateJobAddress calls
// addressRepository.findByJob() after ScheduleJob has already released its transaction before
// making external calls (see ScheduleJob's class comment) - this test reproduces that exact
// condition by giving the repository call no ambient transaction of its own either. A plain
// job.getAddress() would throw LazyInitializationException here (Job.address is a lazy @OneToMany
// with no fetch override).
class AddressRepositoryTest extends ServerApplicationTests {

    @Autowired
    AddressRepository addressRepository;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    WorkspaceRepository workspaceRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Test
    void findByJobDoesNotThrowLazyInitializationException() {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();
        Workspace workspace = workspaceRepository
                .findById(UUID.fromString("5ed411ca-7ab8-4d2f-b591-02d0d5788afc")).orElseThrow();

        Job job = new Job();
        job.setStatus(JobStatus.pending);
        job.setOrganization(organization);
        job.setWorkspace(workspace);
        job = jobRepository.saveAndFlush(job);

        Address target = new Address();
        target.setName("module.network.aws_instance.example");
        target.setType(AddressType.TARGET);
        target.setJob(job);
        addressRepository.saveAndFlush(target);

        List<Address> found = addressRepository.findByJob(job);

        assertThat(found).extracting(Address::getName).contains("module.network.aws_instance.example");
        assertThat(found).extracting(Address::getType).contains(AddressType.TARGET);
    }
}
