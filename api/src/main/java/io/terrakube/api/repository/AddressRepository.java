package io.terrakube.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.address.Address;

import java.util.List;
import java.util.UUID;

public interface AddressRepository extends JpaRepository<Address, UUID> {

    // ExecutorService.validateJobAddress reads this after ScheduleJob has deliberately released
    // its transaction before making external calls (see ScheduleJob's class comment) - Job.address
    // is a lazy @OneToMany with no fetch override, so job.getAddress() has no session left to
    // initialize through by the time it's read there. A plain repository query has no such problem
    // (each Spring Data repository call is its own short, already-committed transaction).
    List<Address> findByJob(Job job);
}
