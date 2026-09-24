package io.terrakube.api;

import io.terrakube.api.rs.job.JobStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The 2.34.0 changeset rewrites stored JobStatus ordinals to names. An ordinal only means something in
 * declaration order, so the mapping in the XML has to match {@link JobStatus#values()} exactly: one
 * update per constant, ordinal i mapped to the i-th name. Reordering the enum without touching the
 * changeset would silently relabel every workspace.
 */
class WorkspaceLastJobStatusChangeSetTest {

    private static final Pattern UPDATE = Pattern.compile(
            "<column name=\"last_job_status\" value=\"([A-Za-z]+)\"/>\\s*<where>last_job_status = '(\\d+)'</where>");

    @Test
    void changeSetMapsEveryOrdinalToTheNameInDeclarationOrder() throws IOException {
        String xml = Files.readString(
                Path.of("src/main/resources/db/changelog/local/changelog-2.34.0-workspace-last-status-enum.xml"));
        List<String> mapped = new ArrayList<>();
        Matcher m = UPDATE.matcher(xml);
        while (m.find()) {
            assertEquals(String.valueOf(mapped.size()), m.group(2), "ordinals must be listed in order");
            mapped.add(m.group(1));
        }
        List<String> expected = new ArrayList<>();
        for (JobStatus status : JobStatus.values()) {
            expected.add(status.name());
        }
        assertEquals(expected, mapped);
    }
}
