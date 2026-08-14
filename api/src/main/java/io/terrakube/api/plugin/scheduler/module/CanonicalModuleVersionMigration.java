package io.terrakube.api.plugin.scheduler.module;

import org.semver4j.Semver;
import liquibase.Scope;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.DatabaseException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.logging.Logger;
import liquibase.resource.ResourceAccessor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CanonicalModuleVersionMigration implements CustomTaskChange {

    private static final Map<String, String> APPROVED_SYSTEM_ALIASES = Map.of("aws-ecs", "aws");
    private static final String VALID_REGISTRY_SYSTEM_PATTERN = "^[A-Za-z0-9]{1,64}$";

    private record VersionRow(String id, String version, String gitTag) {
    }

    private record ModuleMetadata(String provider, String tagPrefix) {
    }

    @Override
    public void execute(Database database) throws CustomChangeException {
        Logger log = Scope.getCurrentScope().getLog(getClass());
        JdbcConnection connection = (JdbcConnection) database.getConnection();
        try {
            for (String moduleId : loadModuleIds(connection)) {
                migrateModule(connection, moduleId, log);
            }
        } catch (SQLException | DatabaseException e) {
            throw new CustomChangeException("Canonical module version migration failed", e);
        }
    }

    private List<String> loadModuleIds(JdbcConnection connection) throws SQLException, DatabaseException {
        List<String> moduleIds = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("SELECT id FROM module");
                ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                moduleIds.add(rs.getString("id"));
            }
        }
        return moduleIds;
    }

    /**
     * Migrates a single module in its own transaction. Any failure here (including an unexpected
     * RuntimeException) is caught and logged rather than propagated, so one bad module is skipped
     * without aborting the migration for every other module - required by the "transactional per
     * module" / "skip and report ambiguous modules rather than guessing" behaviour this change
     * implements.
     */
    private void migrateModule(JdbcConnection connection, String moduleId, Logger log)
            throws SQLException, DatabaseException {
        try {
            connection.setAutoCommit(false);

            ModuleMetadata metadata = loadModuleMetadata(connection, moduleId);
            if (metadata == null) {
                connection.rollback();
                return;
            }

            String aliasedProvider = APPROVED_SYSTEM_ALIASES.getOrDefault(metadata.provider(), metadata.provider());
            if (aliasedProvider == null || !aliasedProvider.matches(VALID_REGISTRY_SYSTEM_PATTERN)) {
                log.info("Skipping module " + moduleId + ": registry system '" + metadata.provider()
                        + "' is invalid and has no approved alias");
                connection.rollback();
                return;
            }

            Map<String, List<VersionRow>> rowsByCanonicalVersion = groupVersionsByCanonicalVersion(connection,
                    moduleId, metadata.tagPrefix());

            List<String> collisions = findCollisions(rowsByCanonicalVersion);
            if (!collisions.isEmpty()) {
                log.warning("Skipping module " + moduleId + " due to version collisions: " + collisions);
                connection.rollback();
                return;
            }

            String latestVersion = applyCanonicalUpdates(connection, moduleId, aliasedProvider, rowsByCanonicalVersion);

            connection.commit();
            log.info("Migrated module " + moduleId + ": provider=" + aliasedProvider + ", versions="
                    + rowsByCanonicalVersion.size() + ", latestVersion=" + latestVersion);
        } catch (Exception e) {
            connection.rollback();
            log.warning("Skipping module " + moduleId + " due to error: " + e.getMessage());
        }
    }

    private ModuleMetadata loadModuleMetadata(JdbcConnection connection, String moduleId)
            throws SQLException, DatabaseException {
        try (PreparedStatement select = connection
                .prepareStatement("SELECT provider, tag_prefix FROM module WHERE id = ?")) {
            select.setString(1, moduleId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new ModuleMetadata(rs.getString("provider"), rs.getString("tag_prefix"));
            }
        }
    }

    private Map<String, List<VersionRow>> groupVersionsByCanonicalVersion(JdbcConnection connection, String moduleId,
            String tagPrefix) throws SQLException, DatabaseException {
        Map<String, List<VersionRow>> rowsByCanonicalVersion = new HashMap<>();
        try (PreparedStatement select = connection
                .prepareStatement("SELECT id, version, git_tag FROM module_version WHERE module_id = ?")) {
            select.setString(1, moduleId);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    VersionRow row = new VersionRow(rs.getString("id"), rs.getString("version"),
                            rs.getString("git_tag"));
                    String rawTag = row.gitTag() != null ? row.gitTag() : row.version();
                    ModuleVersionNormalizer.normalize(rawTag, tagPrefix)
                            .ifPresent(normalized -> rowsByCanonicalVersion
                                    .computeIfAbsent(normalized.canonicalVersion(), key -> new ArrayList<>())
                                    .add(row));
                }
            }
        }
        return rowsByCanonicalVersion;
    }

    private List<String> findCollisions(Map<String, List<VersionRow>> rowsByCanonicalVersion) {
        return rowsByCanonicalVersion.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + ": rows " + entry.getValue())
                .toList();
    }

    /**
     * Applies the alias to {@code module.provider}, canonicalises every {@code module_version} row,
     * and returns the highest canonical version found (or {@code null} if there were none), for the
     * caller to store as {@code module.latest_version}.
     */
    private String applyCanonicalUpdates(JdbcConnection connection, String moduleId, String aliasedProvider,
            Map<String, List<VersionRow>> rowsByCanonicalVersion) throws SQLException, DatabaseException {
        try (PreparedStatement updateProvider = connection
                .prepareStatement("UPDATE module SET provider = ? WHERE id = ?")) {
            updateProvider.setString(1, aliasedProvider);
            updateProvider.setString(2, moduleId);
            updateProvider.executeUpdate();
        }

        String latestVersionKey = null;
        Semver latestVersionParsed = null;
        try (PreparedStatement updateVersion = connection
                .prepareStatement("UPDATE module_version SET version = ?, git_tag = ? WHERE id = ?")) {
            for (Map.Entry<String, List<VersionRow>> entry : rowsByCanonicalVersion.entrySet()) {
                VersionRow row = entry.getValue().get(0);
                String rawTag = row.gitTag() != null ? row.gitTag() : row.version();
                updateVersion.setString(1, entry.getKey());
                updateVersion.setString(2, rawTag);
                updateVersion.setString(3, row.id());
                updateVersion.executeUpdate();

                Semver canonicalVersion = new Semver(entry.getKey());
                if (latestVersionParsed == null || canonicalVersion.compareTo(latestVersionParsed) > 0) {
                    latestVersionParsed = canonicalVersion;
                    latestVersionKey = entry.getKey();
                }
            }
        }

        try (PreparedStatement updateLatest = connection
                .prepareStatement("UPDATE module SET latest_version = ? WHERE id = ?")) {
            updateLatest.setString(1, latestVersionKey);
            updateLatest.setString(2, moduleId);
            updateLatest.executeUpdate();
        }
        return latestVersionKey;
    }

    @Override
    public String getConfirmationMessage() {
        return "Canonicalised module_version.version values and applied approved module.provider aliases";
    }

    @Override
    public void setUp() throws SetupException {
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
    }

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
