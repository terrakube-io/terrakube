package io.terrakube.api.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.yahoo.elide.RefreshableElide;
import com.yahoo.elide.core.dictionary.EntityDictionary;
import io.terrakube.api.PostgresServerApplicationTests;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exports the resource model the UI's JSON:API types are generated from and fails when it drifts from the committed
 * copy, so an entity or changelog change can't land without regenerating it.
 */
class ResourceModelExportTest extends PostgresServerApplicationTests {

    private static final Path COMMITTED = Path.of("..", "ui", "src", "domain", "generated", "resource-model.json");
    private static final String UPDATE_FLAG = "api.model.update";

    @Autowired
    EntityDictionary dictionary;

    @Autowired
    DataSource dataSource;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @Autowired
    RefreshableElide elide;

    private ResourceModelExporter exporter;
    private ResourceModel model;
    private String json;

    @BeforeAll
    void exportModel() {
        exporter = new ResourceModelExporter(
                dictionary,
                new JdbcDatabaseFacts(dataSource, entityManagerFactory),
                new ElideDateFormats(elide.getElide().getElideSettings().getSerdes()));
        model = exporter.export();
        json = ResourceModelJson.write(model);
    }

    @Test
    void exportMatchesCommittedModel() throws IOException {
        if (Boolean.getBoolean(UPDATE_FLAG)) {
            Files.createDirectories(COMMITTED.getParent());
            Files.writeString(COMMITTED, json, StandardCharsets.UTF_8);
            return;
        }
        String committed = Files.exists(COMMITTED) ? Files.readString(COMMITTED, StandardCharsets.UTF_8) : "";
        assertThat(json)
                .as("%s is stale; regenerate it with mvn test -Dtest=ResourceModelExportTest -D%s=true",
                        COMMITTED.normalize(), UPDATE_FLAG)
                .isEqualTo(committed);
    }

    @Test
    void exportIsDeterministic() {
        assertThat(ResourceModelJson.write(exporter.export())).isEqualTo(json);
    }

    @Test
    void exportHasNoProblems() {
        assertThat(model.problems()).isEmpty();
    }

    @Test
    void databaseFactsAnswerOnlyForColumnsThePropertyOwns() {
        DatabaseFacts facts = new JdbcDatabaseFacts(dataSource, entityManagerFactory);
        assertThat(facts.columnNotNull(Workspace.class, "name")).isTrue();
        assertThat(facts.columnNotNull(Workspace.class, "organization")).as("join column").isTrue();
        assertThat(facts.columnNotNull(Workspace.class, "description")).isFalse();
        assertThat(facts.columnNotNull(Organization.class, "collection")).as("mappedBy").isFalse();
        assertThat(facts.columnNotNull(Job.class, "variables")).as("@Transient").isFalse();
        assertThat(facts.columnNotNull(Workspace.class, "noSuchProperty")).isFalse();
    }

    @Test
    void everyDateMemberHasAKnownFormat() {
        Set<String> used = new TreeSet<>();
        model.types().forEach(type -> type.attributes().forEach(attribute -> collectFormats(attribute.type(), used)));
        assertThat(used).isNotEmpty();
        assertThat(model.formats().keySet()).containsAll(used);
    }

    private static void collectFormats(TypeDescriptor descriptor, Set<String> used) {
        switch (descriptor) {
            case TypeDescriptor.DateTime date -> used.add(date.format());
            case TypeDescriptor.ArrayOf array -> collectFormats(array.items(), used);
            case TypeDescriptor.MapOf map -> collectFormats(map.values(), used);
            default -> {
            }
        }
    }
}
